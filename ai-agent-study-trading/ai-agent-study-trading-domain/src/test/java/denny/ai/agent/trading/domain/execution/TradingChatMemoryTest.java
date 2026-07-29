package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;
import denny.ai.agent.trading.api.context.TradingTargetContextKeys;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.support.TestTargets;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingChatMemoryTest {

    private ChatClient.ChatClientRequestSpec request;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        request = mock(ChatClient.ChatClientRequestSpec.class);
        when(request.toolContext(anyMap())).thenReturn(request);
        when(request.advisors(any(Consumer.class))).thenReturn(request);
    }

    @Test
    void shouldInjectTargetSnapshotIntoRequestToolContext() {
        TargetContext target = TestTargets.forTicker("600000");
        DynamicContext dynamicContext = contextWith(target);

        ChatClient.ChatClientRequestSpec result = TradingChatMemory.apply(
                request, TradingContextVO.forTarget(target), dynamicContext, "RiskNode");

        ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
        verify(request).toolContext(captor.capture());
        assertSame(request, result);
        assertEquals(Map.of(TradingTargetContextKeys.TARGET_CONTEXT, target), captor.getValue());
    }

    @Test
    void shouldRejectMissingDynamicTargetBeforeLlmRequest() {
        TargetContext target = TestTargets.forTicker("600000");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TradingChatMemory.apply(request, TradingContextVO.forTarget(target),
                        new DynamicContext(), "RiskNode"));

        assertIdentityBoundary(error);
    }

    @Test
    void shouldRejectWrongDynamicTargetTypeBeforeLlmRequest() {
        TargetContext target = TestTargets.forTicker("600000");
        DynamicContext dynamicContext = new DynamicContext();
        dynamicContext.setValue(TradingTargetContextKeys.TARGET_CONTEXT, "600000.SH");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TradingChatMemory.apply(request, TradingContextVO.forTarget(target),
                        dynamicContext, "RiskNode"));

        assertIdentityBoundary(error);
    }

    @Test
    void shouldRejectRunIdMismatchBeforeLlmRequest() {
        TargetContext dynamicTarget = TestTargets.forTicker("600000");
        TargetContext contextTarget = new TargetContext(UUID.randomUUID().toString(),
                dynamicTarget.targetId(), dynamicTarget.stockName(), dynamicTarget.industry(),
                dynamicTarget.asOfDate());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TradingChatMemory.apply(request, TradingContextVO.forTarget(contextTarget),
                        contextWith(dynamicTarget), "RiskNode"));

        assertIdentityBoundary(error);
    }

    @Test
    void shouldRejectTargetIdMismatchBeforeLlmRequest() {
        TargetContext dynamicTarget = target("600000.SH", UUID.randomUUID().toString());
        TargetContext contextTarget = target("000001.SZ", dynamicTarget.runId());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TradingChatMemory.apply(request, TradingContextVO.forTarget(contextTarget),
                        contextWith(dynamicTarget), "RiskNode"));

        assertIdentityBoundary(error);
    }

    @Test
    void shouldKeepCapturedTargetWhenDynamicContextIsReused() {
        TargetContext first = TestTargets.forTicker("600000");
        TargetContext second = TestTargets.forTicker("000001");
        DynamicContext dynamicContext = contextWith(first);

        TradingChatMemory.apply(request, TradingContextVO.forTarget(first), dynamicContext, "RiskNode");
        ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
        verify(request).toolContext(captor.capture());
        dynamicContext.setValue(TradingTargetContextKeys.TARGET_CONTEXT, second);

        assertEquals(first, captor.getValue().get(TradingTargetContextKeys.TARGET_CONTEXT));
    }

    private DynamicContext contextWith(TargetContext target) {
        DynamicContext dynamicContext = new DynamicContext();
        dynamicContext.setValue(TradingTargetContextKeys.TARGET_CONTEXT, target);
        return dynamicContext;
    }

    private TargetContext target(String targetId, String runId) {
        return new TargetContext(runId, targetId, "测试股票", null,
                LocalDate.of(2026, 7, 28));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private void assertIdentityBoundary(IllegalStateException error) {
        assertTrue(error.getMessage().startsWith("IDENTITY_BOUNDARY_VIOLATION"));
    }
}
