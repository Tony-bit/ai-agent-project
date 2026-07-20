package denny.ai.agent.test.trigger.http;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.domain.service.chatsession.ISessionMemoryPersistenceService;
import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import denny.ai.agent.infrastructure.service.ChatSessionCommandService;
import denny.ai.agent.infrastructure.service.ChatSessionQueryService;
import denny.ai.agent.infrastructure.service.SessionOwnershipService;
import denny.ai.agent.trigger.http.ChatSessionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatSessionControllerIntegrationTest {

    private ChatSessionQueryService queryService;
    private ChatSessionCommandService commandService;
    private SessionOwnershipService ownershipService;
    private ISessionMemoryPersistenceService memoryPersistenceService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(ChatSessionQueryService.class);
        commandService = mock(ChatSessionCommandService.class);
        ownershipService = mock(SessionOwnershipService.class);
        memoryPersistenceService = mock(ISessionMemoryPersistenceService.class);
        CurrentUserContext currentUserContext = new CurrentUserContext();
        currentUserContext.setCurrentUser(AuthUser.builder()
                .userId("user_a")
                .userType(AuthUser.UserType.ACCOUNT)
                .status(AuthUser.UserStatus.ACTIVE)
                .build());
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatSessionController(
                queryService, commandService, ownershipService,
                memoryPersistenceService, currentUserContext)).build();
    }

    @Test
    void listUsesAuthenticatedUserAndIgnoresLegacyUserId() throws Exception {
        mockMvc.perform(get("/api/v1/session/list").param("userId", "user_b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        verify(queryService).getSessionList("user_a", null, null);
    }

    @Test
    void messagesReturnConflictForForeignSession() throws Exception {
        when(queryService.getSessionMessages("user_a", "session_1", null))
                .thenThrow(new ChatSessionQueryService.SessionQueryFailure(
                        ChatSessionQueryService.FailureReason.UNAVAILABLE, "session id unavailable"));

        mockMvc.perform(get("/api/v1/session/session_1/messages"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.info").value("session id unavailable"));
    }

    @Test
    void deleteUsesCurrentUserAndMapsNotFoundAndRunning() throws Exception {
        mockMvc.perform(delete("/api/v1/session/session_1"))
                .andExpect(status().isOk());
        verify(commandService).deleteOwnedSession("user_a", "session_1");

        org.mockito.Mockito.doThrow(new ChatSessionCommandService.SessionCommandFailure(
                        ChatSessionCommandService.FailureReason.NOT_FOUND, "session not found"))
                .when(commandService).deleteOwnedSession("user_a", "missing_session");
        mockMvc.perform(delete("/api/v1/session/missing_session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.info").value("session not found"));

        org.mockito.Mockito.doThrow(new ChatSessionCommandService.SessionCommandFailure(
                        ChatSessionCommandService.FailureReason.RUNNING, "session is running"))
                .when(commandService).deleteOwnedSession("user_a", "running_session");
        mockMvc.perform(delete("/api/v1/session/running_session"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.info").value("session is running"));
    }

    @Test
    void syncMemoryRequiresOwnedSession() throws Exception {
        when(ownershipService.resolve("user_a", "session_1")).thenReturn(SessionAccessState.OWNED);
        mockMvc.perform(post("/api/v1/session/session_1/sync-memory"))
                .andExpect(status().isOk());
        verify(memoryPersistenceService).syncSessionToMemory("user_a", "session_1");

        when(ownershipService.resolve("user_a", "foreign_session")).thenReturn(SessionAccessState.UNAVAILABLE);
        mockMvc.perform(post("/api/v1/session/foreign_session/sync-memory"))
                .andExpect(status().isNotFound());
    }
}
