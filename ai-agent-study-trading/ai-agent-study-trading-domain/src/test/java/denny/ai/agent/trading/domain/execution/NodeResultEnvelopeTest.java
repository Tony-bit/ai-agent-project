package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.api.vo.TargetContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeResultEnvelopeTest {

    @Test
    void injectsTrustedIdentityFromTargetContext() {
        TargetContext target = new TargetContext(UUID.randomUUID().toString(),
                "601318.SH", "中国平安", "保险", LocalDate.of(2026, 7, 22));
        Instant generatedAt = Instant.parse("2026-07-24T08:00:00Z");

        NodeResultEnvelope<TestPayload> envelope = NodeResultEnvelope.wrap(
                target, "technical_analyst", new TestPayload("001309"),
                Clock.fixed(generatedAt, ZoneOffset.UTC));

        assertEquals(target.runId(), envelope.runId());
        assertEquals("601318.SH", envelope.targetId());
        assertEquals(generatedAt, envelope.generatedAt());
        assertEquals("001309", envelope.payload().targetEcho());
    }

    private record TestPayload(String targetEcho) {
    }
}
