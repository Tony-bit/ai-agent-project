package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingLlmCallAuditTest {

    @Test
    void returnsInvocationValue() {
        assertEquals("response", TradingLlmCallAudit.execute(context().getTradingContext(),
                "6002", "FundamentalAnalystNode", () -> "response"));
    }

    @Test
    void rethrowsOriginalInvocationFailure() {
        IllegalStateException failure = new IllegalStateException("provider unavailable");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> TradingLlmCallAudit.execute(context().getTradingContext(),
                        "6002", "FundamentalAnalystNode", () -> {
                            throw failure;
                        }));

        assertSame(failure, thrown);
    }

    @Test
    void rejectsInvocationWithoutTargetContext() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> TradingLlmCallAudit.execute(TradingContextVO.empty(),
                        "6002", "FundamentalAnalystNode", () -> "response"));

        assertEquals("IDENTITY_BOUNDARY_VIOLATION: LLM invocation has no targetContext",
                thrown.getMessage());
    }

    private TradingStateContext context() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("601318");
        return denny.ai.agent.trading.domain.support.TestTargets.stateContext(
                request, new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> true);
    }
}
