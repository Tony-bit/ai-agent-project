package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.service.sse.SseEventSink;
import denny.ai.agent.trading.domain.config.TradingStateContext;

final class TradingPipelineSseGuard {

    private static final String SSE_EVENT_SINK_KEY = "sseEventSink";

    private TradingPipelineSseGuard() {
    }

    static boolean shouldContinue(TradingStateContext context) {
        if (context == null || context.getDynamicContext() == null) {
            return true;
        }
        SseEventSink sink = context.getDynamicContext().getValue(SSE_EVENT_SINK_KEY);
        return sink == null || sink.shouldContinue();
    }
}
