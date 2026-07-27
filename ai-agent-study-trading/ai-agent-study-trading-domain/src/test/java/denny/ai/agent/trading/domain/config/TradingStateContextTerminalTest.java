package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.sse.SseEventSender;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import denny.ai.agent.trading.domain.validation.TradingValidationError;
import denny.ai.agent.trading.domain.validation.ValidationErrorCode;

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

    @Test
    void failedTerminalDeliveryReturnsFalseAndCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger();
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        SseEventSender sender = (type, event) -> attempts.incrementAndGet() > 1;
        TradingStateContext context = denny.ai.agent.trading.domain.support.TestTargets.stateContext(
                request,
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                sender
        );

        assertFalse(context.sendTerminalCompleteOnce());
        assertTrue(context.sendTerminalCompleteOnce());
        assertEquals(2, attempts.get());
    }

    @Test
    void validationFailureSseContainsSafeStructuredContext() {
        List<AutoAgentExecuteResultEntity> events = new ArrayList<>();
        TradingStateContext context = createContext(events);

        context.sendValidationError("TechnicalAnalystNode", List.of(
                new TradingValidationError(
                        ValidationErrorCode.FOREIGN_ENTITY, "internal detail", "payload")));

        assertEquals(1, events.size());
        com.alibaba.fastjson.JSONObject content =
                com.alibaba.fastjson.JSON.parseObject(events.get(0).getContent());
        assertEquals("节点数据校验失败，本次分析已停止", content.getString("message"));
        assertEquals(context.getTargetContext().runId(), content.getString("runId"));
        assertEquals(context.getTargetContext().targetId(), content.getString("targetId"));
        assertEquals("TechnicalAnalystNode", content.getString("nodeName"));
        assertEquals("INVALID", content.getString("validationStatus"));
        assertEquals(List.of("FOREIGN_ENTITY"),
                content.getJSONArray("validationErrors").toJavaList(String.class));
        assertFalse(events.get(0).getContent().contains("internal detail"));
    }

    private TradingStateContext createContext(List<AutoAgentExecuteResultEntity> events) {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        return denny.ai.agent.trading.domain.support.TestTargets.stateContext(
                request,
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> events.add((AutoAgentExecuteResultEntity) event)
        );
    }
}
