package denny.ai.agent.trading.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.validation.TradingValidationError;
import denny.ai.agent.trading.domain.validation.ValidationErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingNodeObservabilityTest {

    @Test
    void recordsPromptHashInputHashSchemaValidationAndLatencyWithoutPromptContent() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("601318");
        TradingStateContext context = denny.ai.agent.trading.domain.support.TestTargets.stateContext(
                request, new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> true);
        TradingNodeObservability observability =
                new TradingNodeObservability(new ObjectMapper().findAndRegisterModules());

        TradingNodeObservability.NodeObservation observation = observability.observe(
                context, "TechnicalAnalystNode", "INVALID",
                List.of(new TradingValidationError(
                        ValidationErrorCode.INPUT_DATA_CONFLICT, "conflict", "currentPrice")),
                37L);

        assertEquals(context.getTargetContext().runId(), observation.runId());
        assertEquals(context.getTargetContext().targetId(), observation.targetId());
        assertEquals("TechnicalAnalystNode", observation.nodeName());
        assertEquals(1, observation.promptVersion());
        assertEquals("0".repeat(64), observation.promptHash());
        assertTrue(observation.inputSnapshotHash().matches("[0-9a-f]{64}"));
        assertEquals("v2", observation.outputSchemaVersion());
        assertEquals("INVALID", observation.validationStatus());
        assertEquals(List.of("INPUT_DATA_CONFLICT"), observation.validationErrors());
        assertEquals(37L, observation.latencyMs());
        assertFalse(observation.toString().contains("test-6003"));
    }
}
