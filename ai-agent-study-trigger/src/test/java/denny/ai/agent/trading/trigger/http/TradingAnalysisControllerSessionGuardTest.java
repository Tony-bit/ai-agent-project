package denny.ai.agent.trading.trigger.http;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.infrastructure.service.SessionExecutionGuard;
import denny.ai.agent.trading.domain.config.TradingStarter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingAnalysisControllerSessionGuardTest {

    private ExecutorService orchestrationExecutor;
    private ExecutorService writerExecutor;
    private SessionExecutionGuard guard;
    private TradingAnalysisController controller;

    @BeforeEach
    void setUp() {
        orchestrationExecutor = mock(ExecutorService.class);
        writerExecutor = mock(ExecutorService.class);
        guard = mock(SessionExecutionGuard.class);
        CurrentUserContext currentUserContext = new CurrentUserContext();
        currentUserContext.setCurrentUser(AuthUser.builder().userId("user_a")
                .userType(AuthUser.UserType.ACCOUNT).status(AuthUser.UserStatus.ACTIVE).build());
        controller = new TradingAnalysisController(mock(TradingStarter.class), orchestrationExecutor,
                writerExecutor, mock(ScheduledExecutorService.class), currentUserContext, guard);
    }

    @Test
    void foreignSessionDoesNotCreateSseSessionOrSubmitAnalysis() {
        when(guard.acquire("user_a", "session_1")).thenThrow(new SessionExecutionGuard.ExecutionFailure(
                SessionExecutionGuard.FailureReason.UNAVAILABLE, "session id unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.analyze(request(), response);

        assertEquals(409, response.getStatus());
        verify(orchestrationExecutor, never()).execute(any());
        verify(writerExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void occupiedSessionDoesNotSubmitAnalysis() {
        when(guard.acquire("user_a", "session_1")).thenThrow(new SessionExecutionGuard.ExecutionFailure(
                SessionExecutionGuard.FailureReason.BUSY, "session is running"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.analyze(request(), response);

        assertEquals(409, response.getStatus());
        verify(orchestrationExecutor, never()).execute(any());
    }

    private TradingAnalysisRequestDTO request() {
        TradingAnalysisRequestDTO request = new TradingAnalysisRequestDTO();
        request.setSessionId("session_1");
        request.setTicker("600519");
        return request;
    }
}
