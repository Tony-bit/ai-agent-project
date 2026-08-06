package denny.ai.agent.config;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.service.stock.StockNameIndexHolder;
import denny.ai.agent.domain.service.stock.StockNameRefreshService;
import denny.ai.agent.domain.service.stock.StockNameSource;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockNameIndexRefreshJobTest {

    @Test
    void run_invokesStartupRefreshExactlyOnce() throws Exception {
        RecordingRefreshService refreshService = new RecordingRefreshService();
        StockNameIndexRefreshJob job = new StockNameIndexRefreshJob(refreshService, new StockNameIndexHolder(fixedClock()));

        job.run(null);

        assertEquals(1, refreshService.calls.get());
        Scheduled scheduled = scheduledAnnotation();
        assertEquals("${spring.ai.trading.stock-name-index.refresh-cron:0 30 3 * * ?}", scheduled.cron());
        assertEquals("${spring.ai.trading.stock-name-index.refresh-zone:Asia/Shanghai}", scheduled.zone());
    }

    @Test
    void refreshOnce_returnsFalseWhenRefreshFails() {
        StockNameIndexRefreshJob job = new StockNameIndexRefreshJob(
                new FailingRefreshService(), new StockNameIndexHolder(fixedClock()));
        boolean success = job.refreshOnce("startup");

        assertFalse(success);
    }

    @Test
    void refreshOnce_preventsOverlappingStartupAndScheduledRefresh() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingRefreshService refreshService = new BlockingRefreshService(entered, release);
        StockNameIndexRefreshJob job = new StockNameIndexRefreshJob(refreshService, new StockNameIndexHolder(fixedClock()));

        Thread first = new Thread(() -> job.refreshOnce("startup"));
        first.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        boolean second = job.refreshOnce("scheduled");
        release.countDown();
        first.join(2000);

        assertFalse(second);
        assertEquals(1, refreshService.calls.get());
    }

    private static Scheduled scheduledAnnotation() throws NoSuchMethodException {
        Method method = StockNameIndexRefreshJob.class.getMethod("runScheduledRefresh");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        return scheduled;
    }

    private static class RecordingRefreshService extends StockNameRefreshService {
        private final AtomicInteger calls = new AtomicInteger();

        RecordingRefreshService() {
            super(dummySource(), new denny.ai.agent.domain.service.stock.StockNameIndexHolder(fixedClock()),
                    fixedClock(), java.time.Duration.ofDays(7));
        }

        @Override
        public RefreshResult refresh() {
            calls.incrementAndGet();
            return new RefreshResult(true, Instant.parse("2026-08-04T00:00:00Z"), StockNameIndex.of(
                    List.of(StockNameRecord.builder().stockName("北方华创").stockCode("002371").build()),
                    Instant.parse("2026-08-04T00:00:00Z"),
                    Instant.parse("2026-08-11T00:00:00Z")), null);
        }
    }

    private static class FailingRefreshService extends StockNameRefreshService {
        FailingRefreshService() {
            super(dummySource(), new denny.ai.agent.domain.service.stock.StockNameIndexHolder(fixedClock()),
                    fixedClock(), java.time.Duration.ofDays(7));
        }

        @Override
        public RefreshResult refresh() {
            return new RefreshResult(false, Instant.parse("2026-08-04T00:00:00Z"),
                    null, new IllegalStateException("boom"));
        }
    }

    private static class BlockingRefreshService extends StockNameRefreshService {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger calls = new AtomicInteger();

        BlockingRefreshService(CountDownLatch entered, CountDownLatch release) {
            super(dummySource(), new denny.ai.agent.domain.service.stock.StockNameIndexHolder(fixedClock()),
                    fixedClock(), java.time.Duration.ofDays(7));
            this.entered = entered;
            this.release = release;
        }

        @Override
        public RefreshResult refresh() {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RefreshResult(true, Instant.parse("2026-08-04T00:00:00Z"), StockNameIndex.of(
                    List.of(StockNameRecord.builder().stockName("北方华创").stockCode("002371").build()),
                    Instant.parse("2026-08-04T00:00:00Z"),
                    Instant.parse("2026-08-11T00:00:00Z")), null);
        }
    }

    private static StockNameSource dummySource() {
        return new StockNameSource() {
            @Override
            public List<StockNameRecord> loadActiveStockNames() {
                return List.of();
            }

            @Override
            public List<StockNameRecord> findByExactName(String stockName) {
                return List.of();
            }
        };
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
    }
}
