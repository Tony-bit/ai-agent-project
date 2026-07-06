package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingStateContextTerminalTest {

    @Test
    void sendTerminalCompleteOnceSendsOnlyOneCompleteEventAndDropsLateSse() {
        List<AutoAgentExecuteResultEntity> events = new ArrayList<>();
        TradingStateContext context = createContext(events);

        assertTrue(context.sendTerminalCompleteOnce());
        assertFalse(context.sendTerminalCompleteOnce());
        context.sendSseResult("debate", "late_debate", "late", false);

        assertEquals(1, events.size());
        assertEquals("trading", events.get(0).getType());
        assertEquals("trading_complete", events.get(0).getSubType());
        assertTrue(events.get(0).getCompleted());
    }

    @Test
    void sendTerminalErrorOnceSendsOnlyOneErrorAndTransitionsToError() {
        List<AutoAgentExecuteResultEntity> events = new ArrayList<>();
        TradingStateContext context = createContext(events);

        assertTrue(context.sendTerminalErrorOnce("节点执行异常: Read timed out"));
        assertFalse(context.sendTerminalErrorOnce("second error"));
        context.sendSseResult("risk", "late_risk", "late", false);

        assertEquals(TradingPhase.ERROR, context.getCurrentPhase());
        assertEquals(1, events.size());
        assertEquals("trading", events.get(0).getType());
        assertEquals("error", events.get(0).getSubType());
        assertTrue(events.get(0).getCompleted());
    }

    private TradingStateContext createContext(List<AutoAgentExecuteResultEntity> events) {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        return new TradingStateContext(
                request,
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> events.add((AutoAgentExecuteResultEntity) event)
        );
    }
}
