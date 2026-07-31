package denny.ai.agent.domain.service.armory.stream;

import java.time.Duration;
import java.util.Objects;

public final class StreamChunkTimeoutPolicy {

    private final Duration firstChunkTimeout;
    private final Duration stallThreshold;
    private final Duration chunkIdleTimeout;
    private final Duration queryAttemptTimeout;
    private final long attemptStartedAtNanos;
    private final long attemptDeadlineNanos;
    private final String logicalCallId;
    private final String modelId;

    public StreamChunkTimeoutPolicy(Duration firstChunkTimeout,
                                    Duration stallThreshold,
                                    Duration chunkIdleTimeout,
                                    Duration queryAttemptTimeout,
                                    long attemptStartedAtNanos,
                                    long attemptDeadlineNanos,
                                    String logicalCallId,
                                    String modelId) {
        this.firstChunkTimeout = requirePositive(firstChunkTimeout, "firstChunkTimeout");
        this.stallThreshold = requirePositive(stallThreshold, "stallThreshold");
        this.chunkIdleTimeout = requirePositive(chunkIdleTimeout, "chunkIdleTimeout");
        this.queryAttemptTimeout = requirePositive(queryAttemptTimeout, "queryAttemptTimeout");
        if (attemptDeadlineNanos <= attemptStartedAtNanos) {
            throw new IllegalArgumentException("attemptDeadlineNanos must be after attemptStartedAtNanos");
        }
        this.attemptStartedAtNanos = attemptStartedAtNanos;
        this.attemptDeadlineNanos = attemptDeadlineNanos;
        this.logicalCallId = logicalCallId;
        this.modelId = modelId;
    }

    public Duration firstChunkTimeout() {
        return firstChunkTimeout;
    }

    public Duration stallThreshold() {
        return stallThreshold;
    }

    public Duration chunkIdleTimeout() {
        return chunkIdleTimeout;
    }

    public Duration queryAttemptTimeout() {
        return queryAttemptTimeout;
    }

    public long attemptStartedAtNanos() {
        return attemptStartedAtNanos;
    }

    public long attemptDeadlineNanos() {
        return attemptDeadlineNanos;
    }

    public String logicalCallId() {
        return logicalCallId;
    }

    public String modelId() {
        return modelId;
    }

    public long remainingAttemptNanos(long nowNanos) {
        return Math.max(0, attemptDeadlineNanos - nowNanos);
    }

    public boolean isAttemptExpired(long nowNanos) {
        return nowNanos >= attemptDeadlineNanos;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
