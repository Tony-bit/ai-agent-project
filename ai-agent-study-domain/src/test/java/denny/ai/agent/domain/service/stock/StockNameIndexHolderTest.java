package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameIndexStatus;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockNameIndexHolderTest {

    @Test
    void currentStatus_isNotReadyBeforeAnySuccessfulPublish() {
        StockNameIndexHolder holder = new StockNameIndexHolder(fixedClock("2026-08-04T00:00:00Z"));

        assertEquals(StockNameIndexStatus.NOT_READY, holder.currentStatus());
        assertTrue(holder.currentIndex().isEmpty());
    }

    @Test
    void currentStatus_isReadyBeforeExpiry() {
        StockNameIndexHolder holder = new StockNameIndexHolder(fixedClock("2026-08-10T00:00:00Z"));
        holder.publish(index("2026-08-04T00:00:00Z", "2026-08-11T00:00:00Z"));

        assertEquals(StockNameIndexStatus.READY, holder.currentStatus());
        assertTrue(holder.readyIndex().isPresent());
    }

    @Test
    void currentStatus_isExpiredAtExpiryBoundary() {
        StockNameIndexHolder holder = new StockNameIndexHolder(fixedClock("2026-08-11T00:00:00Z"));
        holder.publish(index("2026-08-04T00:00:00Z", "2026-08-11T00:00:00Z"));

        assertEquals(StockNameIndexStatus.EXPIRED, holder.currentStatus());
        assertFalse(holder.readyIndex().isPresent());
        assertTrue(holder.currentIndex().isPresent());
    }

    private static StockNameIndex index(String loadedAt, String expiresAt) {
        return StockNameIndex.of(
                List.of(StockNameRecord.builder().stockName("北方华创").stockCode("002371").build()),
                Instant.parse(loadedAt),
                Instant.parse(expiresAt));
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
