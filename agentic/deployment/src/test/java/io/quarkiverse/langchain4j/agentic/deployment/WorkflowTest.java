package io.quarkiverse.langchain4j.agentic.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.agent.MissingArgumentException;
import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.declarative.ErrorHandler;
import dev.langchain4j.agentic.declarative.ExitCondition;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.declarative.SubAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.agentic.deployment.Agents.*;
import io.quarkiverse.langchain4j.openai.testing.internal.OpenAiBaseTest;
import io.quarkiverse.langchain4j.testing.internal.WiremockAware;
import io.quarkus.test.QuarkusUnitTest;

public class WorkflowTest extends OpenAiBaseTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(StoryCreator.class, StoryCreatorWithErrorRecovery.class,
                            CreativeWriter.class, AudienceEditor.class, StyleEditor.class, DummyChatModel.class,
                            StyleReviewLoopAgent.class, StoryCreatorWithReview.class, StyleScorer.class,
                            MedicalExpert.class, TechnicalExpert.class, LegalExpert.class, CategoryRouter.class,
                            RequestCategory.class, ExpertsAgent.class, ExpertRouterAgent.class))
            .overrideRuntimeConfigKey("quarkus.langchain4j.openai.api-key", "whatever")
            .overrideRuntimeConfigKey("quarkus.langchain4j.openai.base-url",
                    WiremockAware.wiremockUrlForConfig("/v1"));

    public interface StoryCreator {

        @SequenceAgent(outputName = "story", subAgents = {
                @SubAgent(type = CreativeWriter.class, outputName = "story"),
                @SubAgent(type = AudienceEditor.class, outputName = "story"),
                @SubAgent(type = StyleEditor.class, outputName = "story")
        })
        String write(@V("topic") String topic, @V("style") String style, @V("audience") String audience);
    }

    @Inject
    StoryCreator storyCreator;

    @Test
    void declarative_sequence_tests() {
        String story = storyCreator.write("dragons and wizards", "fantasy", "young adults");
        System.out.println(story);
        assertThat(story).containsIgnoringCase("dragon").containsIgnoringCase("wizard");
    }

    @Test
    void declarative_sequence_tests_with_error_tests() {
        assertThat(
                assertThrows(AgentInvocationException.class,
                        () -> storyCreator.write(null, "fantasy", "young adults")))
                .hasMessageContaining("topic");
    }

    public interface StoryCreatorWithErrorRecovery extends StoryCreator {

        @ErrorHandler
        static ErrorRecoveryResult errorHandler(ErrorContext errorContext) {
            if (errorContext.agentName().equals("generateStory") &&
                    errorContext.exception() instanceof MissingArgumentException mEx && mEx.argumentName().equals("topic")) {
                errorContext.agenticScope().writeState("topic", "dragons and wizards");
                return ErrorRecoveryResult.retry();
            }
            return ErrorRecoveryResult.throwException();
        }
    }

    @Inject
    StoryCreatorWithErrorRecovery storyCreatorWithErrorRecovery;

    @Test
    void declarative_sequence_with_error_recover_tests() {
        String story = storyCreatorWithErrorRecovery.write(null, "fantasy", "young adults");
        System.out.println(story);
        assertThat(story).containsIgnoringCase("dragon").containsIgnoringCase("wizard");
    }

    public interface StyleReviewLoopAgent {

        @LoopAgent(description = "Review the given story to ensure it aligns with the specified style", outputName = "story", maxIterations = 5, subAgents = {
                @SubAgent(type = StyleScorer.class, outputName = "score"),
                @SubAgent(type = StyleEditor.class, outputName = "story")
        })
        String write(@V("story") String story);

        @ExitCondition
        static boolean exit(@V("score") double score) {
            return score >= 0.8;
        }
    }

    public interface StoryCreatorWithReview {

        @SequenceAgent(outputName = "story", subAgents = {
                @SubAgent(type = CreativeWriter.class, outputName = "story"),
                @SubAgent(type = StyleReviewLoopAgent.class, outputName = "story")
        })
        ResultWithAgenticScope<String> write(@V("topic") String topic, @V("style") String style);
    }

    @Inject
    StoryCreatorWithReview storyCreatorWithReview;

    @Test
    void declarative_sequence_and_loop_tests() {
        ResultWithAgenticScope<String> result = storyCreatorWithReview.write("dragons and wizards", "comedy");
        String story = result.result();
        System.out.println(story);
        assertThat(story).containsIgnoringCase("dragon").containsIgnoringCase("wizard");

        AgenticScope agenticScope = result.agenticScope();
        assertThat(agenticScope.readState("topic")).isEqualTo("dragons and wizards");
        assertThat(agenticScope.readState("style")).isEqualTo("comedy");
        assertThat(story).isEqualTo(agenticScope.readState("story"));
        assertThat(agenticScope.readState("score", 0.0)).isGreaterThanOrEqualTo(0.8);
    }

    public interface ExpertsAgent {

        @ConditionalAgent(outputName = "response", subAgents = {
                @SubAgent(type = MedicalExpert.class, outputName = "response"),
                @SubAgent(type = TechnicalExpert.class, outputName = "response"),
                @SubAgent(type = LegalExpert.class, outputName = "response")
        })
        String askExpert(@V("request") String request);

        @ActivationCondition(MedicalExpert.class)
        static boolean activateMedical(@V("category") RequestCategory category) {
            return category == RequestCategory.MEDICAL;
        }

        @ActivationCondition(TechnicalExpert.class)
        static boolean activateTechnical(@V("category") RequestCategory category) {
            return category == RequestCategory.TECHNICAL;
        }

        @ActivationCondition(LegalExpert.class)
        static boolean activateLegal(@V("category") RequestCategory category) {
            return category == RequestCategory.LEGAL;
        }
    }

    public interface ExpertRouterAgent {

        @SequenceAgent(outputName = "response", subAgents = {
                @SubAgent(type = CategoryRouter.class, outputName = "category"),
                @SubAgent(type = ExpertsAgent.class, outputName = "response")
        })
        ResultWithAgenticScope<String> ask(@V("request") String request);
    }

    @Inject
    ExpertRouterAgent expertRouterAgent;

    @Test
    void declarative_conditional_tests() {
        ResultWithAgenticScope<String> result = expertRouterAgent.ask("I broke my leg what should I do");
        String response = result.result();
        System.out.println(response);

        AgenticScope agenticScope = result.agenticScope();
        assertThat(agenticScope.readState("category")).isEqualTo(RequestCategory.MEDICAL);
    }
}
