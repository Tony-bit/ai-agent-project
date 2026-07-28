package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.prompt.PromptContractMode;
import denny.ai.agent.trading.domain.support.TestTargets;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3IdentityBoundaryEndToEndTest {

    @Test
    void wrongStockNarrativeCommitsWithoutChangingJavaTarget() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("601318");
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamic =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        TargetContext target = TestTargets.forTicker("601318");
        TradingStateContext state = new TradingStateContext(request, dynamic,
                (type, event) -> true, target,
                TestTargets.snapshotFor(target, PromptContractMode.RELAXED_V3));
        FundamentalReportVO report = FundamentalReportVO.builder()
                .summary("001309 德明利价格上涨，建议买入").build();
        NodeExecutionScope scope = new NodeExecutionScope(Instant.now().plusSeconds(5), () -> false);
        AtomicReference<FundamentalReportVO> committed = new AtomicReference<>();

        NodeCommitResult result = new NodeResultCommitter().commitValidated(
                NodeExecutionResult.success(report, scope), TradingPhase.INIT, state,
                "FundamentalAnalystNode", committed::set);

        assertTrue(result.committed());
        assertEquals(report, committed.get());
        assertEquals("601318.SH", state.getTargetContext().targetId());
        assertEquals("601318.SH", state.getTradingContext().getTargetContext().targetId());
    }
}
