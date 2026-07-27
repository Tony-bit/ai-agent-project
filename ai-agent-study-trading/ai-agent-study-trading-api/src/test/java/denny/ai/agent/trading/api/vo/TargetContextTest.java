package denny.ai.agent.trading.api.vo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetContextTest {

    @Test
    void createsCanonicalImmutableTarget() {
        String runId = UUID.randomUUID().toString();
        TargetContext context = new TargetContext(runId, "601318.SH", " 中国平安 ",
                " 保险 ", LocalDate.of(2026, 7, 22));

        assertEquals(runId, context.runId());
        assertEquals("601318.SH", context.targetId());
        assertEquals("601318", context.stockCode());
        assertEquals("中国平安", context.stockName());
        assertEquals("保险", context.industry());
    }

    @Test
    void rejectsInvalidIdentityFields() {
        LocalDate date = LocalDate.of(2026, 7, 22);
        String runId = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class,
                () -> new TargetContext("not-a-uuid", "601318.SH", "中国平安", null, date));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetContext(runId, "601318", "中国平安", null, date));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetContext(runId, "601318.SH", " ", null, date));
        assertThrows(NullPointerException.class,
                () -> new TargetContext(runId, "601318.SH", "中国平安", null, null));
    }
}
