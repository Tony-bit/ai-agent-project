package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.api.vo.TargetContext;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Java 编排层为节点 payload 添加的可信身份外壳。 */
public record NodeResultEnvelope<T>(
        String runId,
        String targetId,
        String nodeName,
        Instant generatedAt,
        T payload
) {

    public NodeResultEnvelope {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName must not be blank");
        }
        nodeName = nodeName.trim();
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static <T> NodeResultEnvelope<T> wrap(TargetContext targetContext,
                                                  String nodeName,
                                                  T payload) {
        return wrap(targetContext, nodeName, payload, Clock.systemUTC());
    }

    static <T> NodeResultEnvelope<T> wrap(TargetContext targetContext,
                                          String nodeName,
                                          T payload,
                                          Clock clock) {
        Objects.requireNonNull(targetContext, "targetContext must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new NodeResultEnvelope<>(targetContext.runId(), targetContext.targetId(),
                nodeName, clock.instant(), payload);
    }
}
