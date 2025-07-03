package io.quarkiverse.langchain4j.sample;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Authenticated
public class PoemResource {

    static final String USER_MESSAGE = """
            Write a short 1 paragraph poem about a Java programming language.
            Please start by greeting the currently logged in user by name and asking to enjoy reading the poem.""";

    @RegisterAiService
    public interface PoemService {
        @UserMessage(USER_MESSAGE)
        @McpToolBox("user-name")
        String writePoem();
    }
    
    @RegisterAiService
    public interface OnciteService {
        @UserMessage("What is possible at Oncite")
        @McpToolBox("oncite")
        String chatWithOncite();
    }
    
    
    @Inject
    PoemService poemService;

    @GET
    @Path("/poem")
    public String getPoem() {
        return poemService.writePoem();
    }
    
    @Inject
    OnciteService onciteService;
    
    @GET
    @Path("/oncite")
    public String getOncite() {
        return onciteService.chatWithOncite();
    }
}
