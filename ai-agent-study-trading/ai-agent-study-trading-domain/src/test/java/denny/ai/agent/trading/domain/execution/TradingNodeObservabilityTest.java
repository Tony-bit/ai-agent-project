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
        assertEquals("6003", observation.clientId());
        assertEquals("TechnicalAnalystNode", observation.nodeName());
        assertEquals(2, observation.promptVersion());
        assertEquals("0".repeat(64), observation.promptHash());
        assertTrue(observation.inputSnapshotHash().matches("[0-9a-f]{64}"));
        assertEquals("STRICT_V2", observation.outputSchemaVersion());
        assertEquals("INVALID", observation.validationStatus());
        assertEquals(List.of("INPUT_DATA_CONFLICT"), observation.validationErrors());
        assertEquals(37L, observation.latencyMs());
        assertFalse(observation.toString().contains("test-6003"));
    }

    @Test
    void usesCanonicalClientIdsForPortfolioAndRiskNodes() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("601318");
        TradingStateContext context = denny.ai.agent.trading.domain.support.TestTargets.stateContext(
                request, new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> true);
        TradingNodeObservability observability =
                new TradingNodeObservability(new ObjectMapper().findAndRegisterModules());

        assertEquals("6009", observe(observability, context, "PortfolioManagerNode").clientId());
        assertEquals("6010", observe(observability, context, "NeutralRiskAnalystNode").clientId());
        assertEquals("6011", observe(observability, context, "ConservativeRiskAnalystNode").clientId());
        assertEquals("6012", observe(observability, context, "AggressiveRiskAnalystNode").clientId());
    }

    private TradingNodeObservability.NodeObservation observe(TradingNodeObservability observability,
                                                              TradingStateContext context,
                                                              String nodeName) {
        return observability.observe(context, nodeName, "VALID", List.of(), 1L);
    }
}
