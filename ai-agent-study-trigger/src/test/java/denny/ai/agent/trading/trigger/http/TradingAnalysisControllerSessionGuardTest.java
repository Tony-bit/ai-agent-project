package denny.ai.agent.trading.trigger.http;

import denny.ai.agent.domain.auth.AuthUser;
import denny.ai.agent.domain.auth.CurrentUserContext;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.sse.SseEventSender;
import denny.ai.agent.infrastructure.service.SessionExecutionGuard;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStarter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingAnalysisControllerSessionGuardTest {

    private ExecutorService orchestrationExecutor;
    private ExecutorService writerExecutor;
    private SessionExecutionGuard guard;
    private TradingStarter tradingStarter;
    private TradingAnalysisController controller;

    @BeforeEach
    void setUp() {
        orchestrationExecutor = mock(ExecutorService.class);
        writerExecutor = mock(ExecutorService.class);
        guard = mock(SessionExecutionGuard.class);
        tradingStarter = mock(TradingStarter.class);
        CurrentUserContext currentUserContext = new CurrentUserContext();
        currentUserContext.setCurrentUser(AuthUser.builder().userId("user_a")
                .userType(AuthUser.UserType.ACCOUNT).status(AuthUser.UserStatus.ACTIVE).build());
        controller = new TradingAnalysisController(tradingStarter, orchestrationExecutor,
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

    @Test
    void directApiPreservesExplicitAnalysisParametersAndOriginalStarterEntry() {
        TradingAnalysisRequestDTO request = request();
        request.setTicker(" 603259.sh ");
        request.setTradeDate("2026-08-01");
        request.setSelectedAnalysts(List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.NEWS));
        request.setMaxDebateRounds(4);
        request.setMaxRiskRounds(3);

        invokeExecuteAnalysis(request);

        StockAnalysisRequestVO captured = captureTradingRequest();
        assertEquals("603259.SH", captured.getTicker());
        assertNull(captured.getStockName());
        assertEquals("2026-08-01", captured.getTradeDate());
        assertEquals(List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.NEWS),
                captured.getSelectedAnalysts());
        assertEquals(4, captured.getMaxDebateRounds());
        assertEquals(3, captured.getMaxRiskRounds());
        assertEquals("session_1", captured.getSessionId());
    }

    @Test
    void directApiKeepsDefaultAnalystsAndRounds() {
        TradingAnalysisRequestDTO request = request();
        request.setSelectedAnalysts(List.of());
        request.setMaxDebateRounds(null);
        request.setMaxRiskRounds(null);

        invokeExecuteAnalysis(request);

        StockAnalysisRequestVO captured = captureTradingRequest();
        assertEquals(List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
                AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS), captured.getSelectedAnalysts());
        assertEquals(2, captured.getMaxDebateRounds());
        assertEquals(1, captured.getMaxRiskRounds());
    }

    private void invokeExecuteAnalysis(TradingAnalysisRequestDTO request) {
        ReflectionTestUtils.invokeMethod(controller, "executeAnalysis", request,
                mock(TradingSseSession.class), request.getSessionId());
    }

    private StockAnalysisRequestVO captureTradingRequest() {
        ArgumentCaptor<StockAnalysisRequestVO> requestCaptor =
                ArgumentCaptor.forClass(StockAnalysisRequestVO.class);
        verify(tradingStarter).start(requestCaptor.capture(),
                any(DefaultAutoAgentExecuteStrategyFactory.DynamicContext.class),
                any(SseEventSender.class));
        return requestCaptor.getValue();
    }

    private TradingAnalysisRequestDTO request() {
        TradingAnalysisRequestDTO request = new TradingAnalysisRequestDTO();
        request.setSessionId("session_1");
        request.setTicker("600519");
        return request;
    }
}
