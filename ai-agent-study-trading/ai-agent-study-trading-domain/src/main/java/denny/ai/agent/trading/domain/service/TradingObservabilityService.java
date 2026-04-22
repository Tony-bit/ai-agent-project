package denny.ai.agent.trading.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 交易 Agent 可观测性服务。
 * <p>
 * 简化版实现，后续可集成 Langfuse/OpenTelemetry 等专业追踪工具。
 */
@Slf4j
@Service
public class TradingObservabilityService {

    private static final String SPAN_PREFIX = "sta.";

    /**
     * Span 句柄（简化版，实际使用中可用 OpenTelemetry Span）
     */
    public static class SpanHandle {
        private final String name;
        private final String traceId;
        private final long startTime;
        private final Map<String, String> metadata;

        public SpanHandle(String name, Map<String, String> metadata) {
            this.name = name;
            this.traceId = UUID.randomUUID().toString().substring(0, 8);
            this.startTime = System.currentTimeMillis();
            this.metadata = new HashMap<>(metadata);
        }

        public String getName() { return name; }
        public String getTraceId() { return traceId; }
        public long getStartTime() { return startTime; }
        public Map<String, String> getMetadata() { return metadata; }

        public long getDuration() {
            return System.currentTimeMillis() - startTime;
        }
    }

    public TradingObservabilityService() {
        log.info("TradingObservabilityService initialized (simplified version)");
    }

    /**
     * 创建分析师 Span
     */
    public SpanHandle startAnalystSpan(String analystType, String sessionId) {
        String spanName = SPAN_PREFIX + "analyst." + analystType.toLowerCase();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("session_id", sessionId);
        metadata.put("node_type", "analyst");
        metadata.put("analyst_type", analystType);

        SpanHandle span = new SpanHandle(spanName, metadata);
        log.debug("Started analyst span: {} [traceId={}]", spanName, span.getTraceId());
        return span;
    }

    /**
     * 创建辩论 Span
     */
    public SpanHandle startDebateSpan(int round, String sessionId) {
        String spanName = SPAN_PREFIX + "debate.round" + round;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("session_id", sessionId);
        metadata.put("node_type", "debate");
        metadata.put("round", String.valueOf(round));

        SpanHandle span = new SpanHandle(spanName, metadata);
        log.debug("Started debate span: {} [traceId={}]", spanName, span.getTraceId());
        return span;
    }

    /**
     * 创建交易员 Span
     */
    public SpanHandle startTraderSpan(String sessionId) {
        String spanName = SPAN_PREFIX + "trader";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("session_id", sessionId);
        metadata.put("node_type", "trader");

        SpanHandle span = new SpanHandle(spanName, metadata);
        log.debug("Started trader span: {} [traceId={}]", spanName, span.getTraceId());
        return span;
    }

    /**
     * 创建风控 Span
     */
    public SpanHandle startRiskSpan(String sessionId) {
        String spanName = SPAN_PREFIX + "risk";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("session_id", sessionId);
        metadata.put("node_type", "risk");

        SpanHandle span = new SpanHandle(spanName, metadata);
        log.debug("Started risk span: {} [traceId={}]", spanName, span.getTraceId());
        return span;
    }

    /**
     * 创建最终决策 Span
     */
    public SpanHandle startDecisionSpan(String sessionId) {
        String spanName = SPAN_PREFIX + "decision";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("session_id", sessionId);
        metadata.put("node_type", "decision");

        SpanHandle span = new SpanHandle(spanName, metadata);
        log.debug("Started decision span: {} [traceId={}]", spanName, span.getTraceId());
        return span;
    }

    /**
     * 结束 Span
     */
    public void endSpan(SpanHandle span, Map<String, Object> output) {
        if (span != null) {
            log.info("Ended span: {} [traceId={}, duration={}ms, output={}]",
                    span.getName(), span.getTraceId(), span.getDuration(), output);
        }
    }

    /**
     * 记录错误
     */
    public void recordError(SpanHandle span, Exception e) {
        if (span != null) {
            log.error("Recorded error in span: {} [traceId={}]", span.getName(), span.getTraceId(), e);
        } else {
            log.error("Recorded error: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行带 Span 的操作
     */
    public <T> T executeWithSpan(String spanName, Supplier<T> operation) {
        SpanHandle span = new SpanHandle(spanName, new HashMap<>());

        try {
            T result = operation.get();
            endSpan(span, Map.of("status", "success", "result", result.toString()));
            return result;
        } catch (Exception e) {
            recordError(span, e);
            throw e;
        }
    }
}
