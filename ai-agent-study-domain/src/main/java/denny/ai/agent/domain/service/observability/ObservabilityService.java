package denny.ai.agent.domain.service.observability;

import java.util.Map;

/**
 * 统一可观测性接口，避免业务代码直接依赖具体平台 SDK。
 */
public interface ObservabilityService {

    String startTrace(String sessionId, String input, Map<String, Object> metadata);

    String startSpan(String traceId, String spanName, Map<String, Object> metadata);

    void logGeneration(String traceId,
                       String spanId,
                       String model,
                       String prompt,
                       String output,
                       Map<String, Object> metadata,
                       Map<String, Object> tokenUsage);

    void logScore(String traceId,
                  String scoreName,
                  Double value,
                  String comment,
                  Map<String, Object> metadata);

    void updateTraceMetadata(String traceId, Map<String, Object> metadata);

    void endSpan(String spanId, boolean success, String errorMessage);

    void endTrace(String traceId, String output, Map<String, Object> metadata);
}
