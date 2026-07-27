package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory;
import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.support.TestTargets;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingStateContextIsolationTest {

    @Test
    void parallelTargetsDoNotShareMutableContextValidationStateOrMemoryNamespace() {
        TradingStateContext first = state("001309.SZ", "Demingli");
        TradingStateContext second = state("601318.SH", "Ping An");

        CompletableFuture<Void> firstWrite = CompletableFuture.runAsync(() -> {
            first.getTradingContext().setFundamentalReport(
                    FundamentalReportVO.builder().summary("first report").build());
            first.getValidationRegistry().markValid("FundamentalAnalystNode");
        });
        CompletableFuture<Void> secondWrite = CompletableFuture.runAsync(() ->
                second.getTradingContext().setFundamentalReport(
                        FundamentalReportVO.builder().summary("second report").build()));
        CompletableFuture.allOf(firstWrite, secondWrite).join();

        assertNotSame(first.getTradingContext(), second.getTradingContext());
        assertNotSame(first.getValidationRegistry(), second.getValidationRegistry());
        assertEquals("first report", first.getTradingContext().getFundamentalReport().getSummary());
        assertEquals("second report", second.getTradingContext().getFundamentalReport().getSummary());
        assertTrue(first.getValidationRegistry().isValid("FundamentalAnalystNode"));
        assertFalse(second.getValidationRegistry().isValid("FundamentalAnalystNode"));
        assertNotEquals(
                TradingNamespaceKeyFactory.chatMemory(
                        "same-session", first.getTargetContext(), "FundamentalAnalystNode"),
                TradingNamespaceKeyFactory.chatMemory(
                        "same-session", second.getTargetContext(), "FundamentalAnalystNode"));
        assertNull(first.getTradingContext().getTechnicalReport());
        assertNull(second.getTradingContext().getTechnicalReport());
    }

    private TradingStateContext state(String targetId, String stockName) {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker(targetId.substring(0, 6));
        request.setSessionId("same-session");
        TargetContext target = new TargetContext(UUID.randomUUID().toString(), targetId,
                stockName, null, LocalDate.of(2026, 7, 22));
        return new TradingStateContext(request,
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> true, target, TestTargets.snapshotFor(target));
    }
}
