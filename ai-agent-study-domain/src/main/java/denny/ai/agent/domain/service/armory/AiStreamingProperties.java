package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.StreamingTimeoutConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Slf4j
@ConfigurationProperties(prefix = "ai.client.streaming")
public class AiStreamingProperties implements InitializingBean {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_FIRST_CHUNK_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_STALL_THRESHOLD = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CHUNK_IDLE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration DEFAULT_QUERY_ATTEMPT_TIMEOUT = Duration.ofSeconds(150);

    private TimeoutMode timeoutMode = TimeoutMode.LAYERED;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Duration firstChunkTimeout;
    private Duration stallThreshold;
    private Duration chunkIdleTimeout;
    private Duration queryAttemptTimeout;

    /** @deprecated use {@link #firstChunkTimeout}. */
    @Deprecated
    private Duration firstContentTimeout;
    /** @deprecated use {@link #stallThreshold}. */
    @Deprecated
    private Duration idleTimeout;
    /** @deprecated use {@link #queryAttemptTimeout}. */
    @Deprecated
    private Duration totalTimeout;
    private final Set<String> loggedLegacyFields = ConcurrentHashMap.newKeySet();

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        validateConfiguredDuration(connectTimeout, "ai.client.streaming.connect-timeout");
        validateConfiguredDuration(firstChunkTimeout, "ai.client.streaming.first-chunk-timeout");
        validateConfiguredDuration(stallThreshold, "ai.client.streaming.stall-threshold");
        validateConfiguredDuration(chunkIdleTimeout, "ai.client.streaming.chunk-idle-timeout");
        validateConfiguredDuration(queryAttemptTimeout, "ai.client.streaming.query-attempt-timeout");
        validateConfiguredDuration(firstContentTimeout, "ai.client.streaming.first-content-timeout");
        validateConfiguredDuration(idleTimeout, "ai.client.streaming.idle-timeout");
        validateConfiguredDuration(totalTimeout, "ai.client.streaming.total-timeout");
        resolveInternal(null, false);
    }

    public StreamingTimeouts resolve(StreamingTimeoutConfig override) {
        validateModelOverride(override);
        return resolveInternal(override, true);
    }

    private StreamingTimeouts resolveInternal(StreamingTimeoutConfig override, boolean warnLegacy) {
        TimeoutMode effectiveMode = Objects.requireNonNullElse(timeoutMode, TimeoutMode.LAYERED);
        Duration effectiveConnect = Objects.requireNonNullElse(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        Duration effectiveFirstChunk = resolveDuration(
                override == null ? null : override.getFirstChunkTimeoutMs(),
                override == null ? null : override.getFirstContentTimeoutMs(),
                firstChunkTimeout, firstContentTimeout, DEFAULT_FIRST_CHUNK_TIMEOUT,
                "first-content-timeout", warnLegacy);
        Duration effectiveStall = resolveDuration(
                override == null ? null : override.getStallThresholdMs(),
                override == null ? null : override.getIdleTimeoutMs(),
                stallThreshold, idleTimeout, DEFAULT_STALL_THRESHOLD,
                "idle-timeout", warnLegacy);
        Duration effectiveChunkIdle = resolveDuration(
                override == null ? null : override.getChunkIdleTimeoutMs(),
                null, chunkIdleTimeout, null, DEFAULT_CHUNK_IDLE_TIMEOUT,
                null, false);
        Duration effectiveAttempt = resolveDuration(
                override == null ? null : override.getQueryAttemptTimeoutMs(),
                override == null ? null : override.getTotalTimeoutMs(),
                queryAttemptTimeout, totalTimeout, DEFAULT_QUERY_ATTEMPT_TIMEOUT,
                "total-timeout", warnLegacy);

        StreamingTimeouts resolved = new StreamingTimeouts(effectiveMode, effectiveConnect,
                effectiveFirstChunk, effectiveStall, effectiveChunkIdle, effectiveAttempt);
        validateResolved(resolved);
        return resolved;
    }

    private Duration resolveDuration(Long modelNew, Long modelLegacy,
                                     Duration globalNew, Duration globalLegacy,
                                     Duration defaultValue, String legacyName,
                                     boolean warnLegacy) {
        if (modelNew != null) {
            return Duration.ofMillis(modelNew);
        }
        if (modelLegacy != null) {
            warnLegacy(legacyName, "model", warnLegacy);
            return Duration.ofMillis(modelLegacy);
        }
        if (globalNew != null) {
            return globalNew;
        }
        if (globalLegacy != null) {
            warnLegacy(legacyName, "global", warnLegacy);
            return globalLegacy;
        }
        return defaultValue;
    }

    private void warnLegacy(String field, String level, boolean enabled) {
        if (enabled && field != null && loggedLegacyFields.add(level + ":" + field)) {
            log.warn("Deprecated streaming timeout field used | field={} | level={}", field, level);
        }
    }

    private void validateModelOverride(StreamingTimeoutConfig override) {
        if (override == null) {
            return;
        }
        validateMillis(override.getFirstChunkTimeoutMs(), "streamingTimeout.firstChunkTimeoutMs");
        validateMillis(override.getStallThresholdMs(), "streamingTimeout.stallThresholdMs");
        validateMillis(override.getChunkIdleTimeoutMs(), "streamingTimeout.chunkIdleTimeoutMs");
        validateMillis(override.getQueryAttemptTimeoutMs(), "streamingTimeout.queryAttemptTimeoutMs");
        validateMillis(override.getFirstContentTimeoutMs(), "streamingTimeout.firstContentTimeoutMs");
        validateMillis(override.getIdleTimeoutMs(), "streamingTimeout.idleTimeoutMs");
        validateMillis(override.getTotalTimeoutMs(), "streamingTimeout.totalTimeoutMs");
    }

    private void validateResolved(StreamingTimeouts timeouts) {
        validateConfiguredDuration(timeouts.connectTimeout(), "ai.client.streaming.connect-timeout");
        validateConfiguredDuration(timeouts.firstChunkTimeout(), "effective first chunk timeout");
        validateConfiguredDuration(timeouts.stallThreshold(), "effective stall threshold");
        validateConfiguredDuration(timeouts.chunkIdleTimeout(), "effective chunk idle timeout");
        validateConfiguredDuration(timeouts.queryAttemptTimeout(), "effective query attempt timeout");
        if (timeouts.timeoutMode() == TimeoutMode.LAYERED) {
            requireLessThan(timeouts.connectTimeout(), timeouts.firstChunkTimeout(),
                    "connectTimeout must be less than firstChunkTimeout");
            requireLessThan(timeouts.firstChunkTimeout(), timeouts.queryAttemptTimeout(),
                    "firstChunkTimeout must be less than queryAttemptTimeout");
            requireLessThan(timeouts.stallThreshold(), timeouts.chunkIdleTimeout(),
                    "stallThreshold must be less than chunkIdleTimeout");
            requireLessThan(timeouts.chunkIdleTimeout(), timeouts.queryAttemptTimeout(),
                    "chunkIdleTimeout must be less than queryAttemptTimeout");
        }
    }

    private void requireLessThan(Duration lower, Duration upper, String message) {
        if (lower.compareTo(upper) >= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validateConfiguredDuration(Duration value, String property) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    private void validateMillis(Long value, String property) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    public enum TimeoutMode {
        LAYERED,
        LEGACY
    }

    public static final class StreamingTimeouts {
        private final TimeoutMode timeoutMode;
        private final Duration connectTimeout;
        private final Duration firstChunkTimeout;
        private final Duration stallThreshold;
        private final Duration chunkIdleTimeout;
        private final Duration queryAttemptTimeout;

        public StreamingTimeouts(TimeoutMode timeoutMode,
                                 Duration connectTimeout,
                                 Duration firstChunkTimeout,
                                 Duration stallThreshold,
                                 Duration chunkIdleTimeout,
                                 Duration queryAttemptTimeout) {
            this.timeoutMode = Objects.requireNonNull(timeoutMode, "timeoutMode must not be null");
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
            this.firstChunkTimeout = Objects.requireNonNull(firstChunkTimeout, "firstChunkTimeout must not be null");
            this.stallThreshold = Objects.requireNonNull(stallThreshold, "stallThreshold must not be null");
            this.chunkIdleTimeout = Objects.requireNonNull(chunkIdleTimeout, "chunkIdleTimeout must not be null");
            this.queryAttemptTimeout = Objects.requireNonNull(queryAttemptTimeout, "queryAttemptTimeout must not be null");
        }

        /**
         * Compatibility constructor for tests and callers that still express legacy semantics.
         */
        public StreamingTimeouts(Duration connectTimeout,
                                 Duration firstContentTimeout,
                                 Duration idleTimeout,
                                 Duration totalTimeout) {
            this(TimeoutMode.LEGACY, connectTimeout, firstContentTimeout, idleTimeout,
                    DEFAULT_CHUNK_IDLE_TIMEOUT, totalTimeout);
        }

        public TimeoutMode timeoutMode() {
            return timeoutMode;
        }

        public Duration connectTimeout() {
            return connectTimeout;
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

        /** @deprecated use {@link #firstChunkTimeout()}. */
        @Deprecated
        public Duration firstContentTimeout() {
            return firstChunkTimeout;
        }

        /** @deprecated use {@link #stallThreshold()}. */
        @Deprecated
        public Duration idleTimeout() {
            return stallThreshold;
        }

        /** @deprecated use {@link #queryAttemptTimeout()}. */
        @Deprecated
        public Duration totalTimeout() {
            return queryAttemptTimeout;
        }
    }
}
