package denny.ai.agent.domain.service.armory.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public final class StreamTimeoutRetryMetrics {

    public enum Decision {
        SCHEDULED,
        DISABLED,
        EXHAUSTED,
        HARD_EXCLUDED
    }

    private final MeterRegistry meterRegistry;

    public StreamTimeoutRetryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(StreamTimeoutType timeoutType, Decision decision) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("llm_stream_timeout_retry_decisions_total")
                .tag("timeoutType", timeoutType.name())
                .tag("decision", decision.name())
                .register(meterRegistry)
                .increment();
    }
}
