package denny.ai.agent.domain.service.armory.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamTimeoutRetryMetricsTest {

    @Test
    void should_record_each_low_cardinality_decision_once() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StreamTimeoutRetryMetrics metrics = new StreamTimeoutRetryMetrics(registry);

        for (StreamTimeoutRetryMetrics.Decision decision
                : StreamTimeoutRetryMetrics.Decision.values()) {
            metrics.record(StreamTimeoutType.FIRST_CHUNK, decision);
            Counter counter = registry.get("llm_stream_timeout_retry_decisions_total")
                    .tag("timeoutType", "FIRST_CHUNK")
                    .tag("decision", decision.name())
                    .counter();
            assertEquals(1.0, counter.count());
            Set<String> tagKeys = counter.getId().getTags().stream()
                    .map(tag -> tag.getKey())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("timeoutType", "decision"), tagKeys);
        }
    }

    @Test
    void should_be_noop_without_registry() {
        StreamTimeoutRetryMetrics metrics = new StreamTimeoutRetryMetrics(null);
        assertDoesNotThrow(() -> metrics.record(StreamTimeoutType.CHUNK_IDLE,
                StreamTimeoutRetryMetrics.Decision.SCHEDULED));
    }
}
