package denny.ai.agent.trigger.http;

import denny.ai.agent.api.dto.AutoAgentRequestDTO;
import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.excute.IExecuteStrategy;
import denny.ai.agent.domain.service.oss.OSSUploadService;
import denny.ai.agent.infrastructure.service.SessionExecutionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentControllerSessionGuardTest {

    private IExecuteStrategy strategy;
    private ThreadPoolExecutor executor;
    private SessionExecutionGuard guard;
    private CurrentUserContext currentUserContext;
    private AiAgentController controller;

    @BeforeEach
    void setUp() {
        strategy = mock(IExecuteStrategy.class);
        executor = mock(ThreadPoolExecutor.class);
        guard = mock(SessionExecutionGuard.class);
        currentUserContext = new CurrentUserContext();
        currentUserContext.setCurrentUser(AuthUser.builder().userId("user_a")
                .userType(AuthUser.UserType.ACCOUNT).status(AuthUser.UserStatus.ACTIVE).build());
        controller = new AiAgentController(strategy, executor, mock(OSSUploadService.class),
                currentUserContext, guard);
    }

    @Test
    void foreignSessionDoesNotSubmitAsyncWork() {
        when(guard.acquire("user_a", "session_1")).thenThrow(new SessionExecutionGuard.ExecutionFailure(
                SessionExecutionGuard.FailureReason.UNAVAILABLE, "session id unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.autoAgent(request("user_b"), response);

        assertEquals(409, response.getStatus());
        verify(executor, never()).execute(any());
    }

    @Test
    void occupiedSessionDoesNotSubmitInspectionWork() {
        when(guard.acquire("user_a", "session_1")).thenThrow(new SessionExecutionGuard.ExecutionFailure(
                SessionExecutionGuard.FailureReason.BUSY, "session is running"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.inspection(request("user_b"), response);

        assertEquals(409, response.getStatus());
        verify(executor, never()).execute(any());
    }

    @Test
    void currentUserOverridesRequestUserAndLeaseClosesAfterExecution() throws Exception {
        SessionExecutionGuard.ExecutionLease lease = mock(SessionExecutionGuard.ExecutionLease.class);
        when(guard.acquire("user_a", "session_1")).thenReturn(lease);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        controller.autoAgent(request("user_b"), new MockHttpServletResponse());

        ArgumentCaptor<ExecuteCommandEntity> command = ArgumentCaptor.forClass(ExecuteCommandEntity.class);
        verify(strategy).execute(command.capture(), any());
        assertEquals("user_a", command.getValue().getUserId());
        verify(lease).close();
    }

    private AutoAgentRequestDTO request(String forgedUserId) {
        return AutoAgentRequestDTO.builder()
                .userId(forgedUserId)
                .sessionId("session_1")
                .message("hello")
                .maxStep(3)
                .build();
    }
}
