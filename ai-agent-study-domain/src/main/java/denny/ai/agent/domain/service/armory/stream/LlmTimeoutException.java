package denny.ai.agent.domain.service.armory.stream;

import java.time.Duration;
import java.util.Objects;

public abstract class LlmTimeoutException extends RuntimeException {

    private final Duration configuredTimeout;
    private final Duration effectiveTimeout;
    private final TimeoutDeadlineOwner deadlineOwner;
    private final Duration elapsed;
    private final long observedChunkCount;
    private final String logicalCallId;
    private final String modelId;

    protected LlmTimeoutException(String message,
                                  Duration configuredTimeout,
                                  Duration effectiveTimeout,
                                  TimeoutDeadlineOwner deadlineOwner,
                                  Duration elapsed,
                                  long observedChunkCount,
                                  String logicalCallId,
                                  String modelId) {
        super(message);
        this.configuredTimeout = requireDuration(configuredTimeout, "configuredTimeout");
        this.effectiveTimeout = requireDuration(effectiveTimeout, "effectiveTimeout");
        this.deadlineOwner = Objects.requireNonNull(deadlineOwner, "deadlineOwner must not be null");
        this.elapsed = requireNonNegative(elapsed, "elapsed");
        if (observedChunkCount < 0) {
            throw new IllegalArgumentException("observedChunkCount must not be negative");
        }
        this.observedChunkCount = observedChunkCount;
        this.logicalCallId = logicalCallId;
        this.modelId = modelId;
    }

    public Duration getConfiguredTimeout() {
        return configuredTimeout;
    }

    public Duration getEffectiveTimeout() {
        return effectiveTimeout;
    }

    public TimeoutDeadlineOwner getDeadlineOwner() {
        return deadlineOwner;
    }

    public Duration getElapsed() {
        return elapsed;
    }

    public long getObservedChunkCount() {
        return observedChunkCount;
    }

    public String getLogicalCallId() {
        return logicalCallId;
    }

    public String getModelId() {
        return modelId;
    }

    private static Duration requireDuration(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
