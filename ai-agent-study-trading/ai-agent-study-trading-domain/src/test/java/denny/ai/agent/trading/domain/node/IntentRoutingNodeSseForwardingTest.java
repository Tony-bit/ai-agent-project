package denny.ai.agent.trading.domain.node;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStarter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class IntentRoutingNodeSseForwardingTest {

    @Test
    void forwardsTradingEventsToTheAutoAgentSseStream() throws Exception {
        TradingStarter starter = mock(TradingStarter.class);
        CapturingIntentRoutingNode node = new CapturingIntentRoutingNode();
        ReflectionTestUtils.setField(node, "starter", starter);

        DefaultAutoAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        context.setValue(IntentRoutingNode.TRADING_REQUEST_KEY,
                StockAnalysisRequestVO.builder().ticker("001309").build());

        AutoAgentExecuteResultEntity terminalEvent = AutoAgentExecuteResultEntity.builder()
                .type("trading")
                .subType("trading_complete")
                .content("交易分析完成")
                .completed(true)
                .build();
        AtomicBoolean forwardingResult = new AtomicBoolean();

        doAnswer(invocation -> {
            denny.ai.agent.domain.service.sse.SseEventSender sender = invocation.getArgument(2);
            forwardingResult.set(sender.send("trading", terminalEvent));
            return null;
        }).when(starter).start(any(), any(), any());

        node.get(ExecuteCommandEntity.builder().build(), context);

        assertEquals(1, node.forwardedEvents.size());
        assertSame(terminalEvent, node.forwardedEvents.get(0));
        assertEquals(true, forwardingResult.get());
    }

    private static final class CapturingIntentRoutingNode extends IntentRoutingNode {
        private final List<AutoAgentExecuteResultEntity> forwardedEvents = new ArrayList<>();

        @Override
        protected boolean sendSseResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                        AutoAgentExecuteResultEntity result) {
            forwardedEvents.add(result);
            return true;
        }
    }
}
