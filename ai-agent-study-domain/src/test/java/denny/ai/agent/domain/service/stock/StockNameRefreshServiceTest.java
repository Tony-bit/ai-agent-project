package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameIndexStatus;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockNameRefreshServiceTest {

    @Test
    void refresh_publishesReadyIndexWithLoadedAtAndExpiresAt() {
        Clock clock = fixedClock("2026-08-04T00:00:00Z");
        StockNameIndexHolder holder = new StockNameIndexHolder(clock);
        StockNameRefreshService service = new StockNameRefreshService(
                fixedSource(List.of(
                        record("北方华创", "002371"),
                        record("华创云信", "600155")
                )),
                holder,
                clock,
                Duration.ofDays(7));

        StockNameRefreshService.RefreshResult result = service.refresh();

        assertTrue(result.success());
        assertEquals(StockNameIndexStatus.READY, holder.currentStatus());
        StockNameIndex index = holder.readyIndex().orElseThrow();
        assertEquals(Instant.parse("2026-08-04T00:00:00Z"), index.getLoadedAt());
        assertEquals(Instant.parse("2026-08-11T00:00:00Z"), index.getExpiresAt());
        assertEquals(2, index.getRecords().size());
        assertNull(result.error());
    }

    @Test
    void refresh_keepsNotReadyWhenFirstLoadFails() {
        Clock clock = fixedClock("2026-08-04T00:00:00Z");
        StockNameIndexHolder holder = new StockNameIndexHolder(clock);
        StockNameRefreshService service = new StockNameRefreshService(
                throwingSource(new IllegalStateException("Tushare timeout")),
                holder,
                clock,
                Duration.ofDays(7));

        StockNameRefreshService.RefreshResult result = service.refresh();

        assertFalse(result.success());
        assertEquals(StockNameIndexStatus.NOT_READY, holder.currentStatus());
        assertTrue(holder.currentIndex().isEmpty());
        assertNotNull(result.error());
    }

    @Test
    void refresh_rejectsEmptyDirectoryAndKeepsOldReadyIndex() {
        Clock clock = fixedClock("2026-08-08T00:00:00Z");
        StockNameIndexHolder holder = new StockNameIndexHolder(clock);
        holder.publish(StockNameIndex.of(
                List.of(record("北方华创", "002371")),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-11T00:00:00Z")));
        StockNameRefreshService service = new StockNameRefreshService(
                fixedSource(List.of()),
                holder,
                clock,
                Duration.ofDays(7));

        StockNameRefreshService.RefreshResult result = service.refresh();

        assertFalse(result.success());
        assertEquals(StockNameIndexStatus.READY, holder.currentStatus());
        assertEquals(1, holder.readyIndex().orElseThrow().getRecords().size());
        assertNotNull(result.error());
    }

    @Test
    void refresh_rejectsInvalidBatchAndDoesNotPublishHalfFinishedIndex() {
        Clock clock = fixedClock("2026-08-08T00:00:00Z");
        StockNameIndexHolder holder = new StockNameIndexHolder(clock);
        holder.publish(StockNameIndex.of(
                List.of(record("北方华创", "002371")),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-11T00:00:00Z")));
        StockNameRefreshService service = new StockNameRefreshService(
                fixedSource(List.of(
                        record("平安银行", "000001"),
                        record("平安银行", "bad")
                )),
                holder,
                clock,
                Duration.ofDays(7));

        StockNameRefreshService.RefreshResult result = service.refresh();

        assertFalse(result.success());
        assertEquals(List.of(record("北方华创", "002371")), holder.readyIndex().orElseThrow().getRecords());
        assertNotNull(result.error());
    }

    @Test
    void currentStatus_becomesExpiredAtSevenDayBoundary() {
        StockNameIndexHolder holder = new StockNameIndexHolder(fixedClock("2026-08-11T00:00:00Z"));
        holder.publish(StockNameIndex.of(
                List.of(record("北方华创", "002371")),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-11T00:00:00Z")));

        assertEquals(StockNameIndexStatus.EXPIRED, holder.currentStatus());
    }

    @Test
    void refresh_recoversFromNotReadyToReady() {
        Clock clock = fixedClock("2026-08-12T00:00:00Z");
        StockNameIndexHolder holder = new StockNameIndexHolder(clock);
        StockNameRefreshService service = new StockNameRefreshService(
                fixedSource(List.of(record("北方华创", "002371"))),
                holder,
                clock,
                Duration.ofDays(7));

        StockNameRefreshService.RefreshResult result = service.refresh();

        assertTrue(result.success());
        assertEquals(StockNameIndexStatus.READY, holder.currentStatus());
        assertEquals(Instant.parse("2026-08-19T00:00:00Z"), holder.readyIndex().orElseThrow().getExpiresAt());
    }

    @Test
    void refresh_recoversFromExpiredToReady() {
        Clock clock = fixedClock("2026-08-12T00:00:00Z");
        StockNameIndexHolder holder = new StockNameIndexHolder(clock);
        holder.publish(StockNameIndex.of(
                List.of(record("旧索引", "000001")),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-08T00:00:00Z")));
        StockNameRefreshService service = new StockNameRefreshService(
                fixedSource(List.of(record("北方华创", "002371"))),
                holder,
                clock,
                Duration.ofDays(7));

        StockNameRefreshService.RefreshResult result = service.refresh();

        assertTrue(result.success());
        assertEquals(StockNameIndexStatus.READY, holder.currentStatus());
        assertEquals(List.of(record("北方华创", "002371")), holder.readyIndex().orElseThrow().getRecords());
    }

    private static StockNameSource fixedSource(List<StockNameRecord> records) {
        return new StockNameSource() {
            @Override
            public List<StockNameRecord> loadActiveStockNames() {
                return records;
            }

            @Override
            public List<StockNameRecord> findByExactName(String stockName) {
                return List.of();
            }
        };
    }

    private static StockNameSource throwingSource(RuntimeException exception) {
        return new StockNameSource() {
            @Override
            public List<StockNameRecord> loadActiveStockNames() {
                throw exception;
            }

            @Override
            public List<StockNameRecord> findByExactName(String stockName) {
                return List.of();
            }
        };
    }

    private static StockNameRecord record(String stockName, String stockCode) {
        return StockNameRecord.builder()
                .stockName(stockName)
                .stockCode(stockCode)
                .build();
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
