package denny.ai.agent.config;

import denny.ai.agent.domain.service.stock.StockNameRefreshService;
import denny.ai.agent.domain.service.stock.StockNameIndexHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs startup warmup and scheduled refresh for the stock name index.
 */
@Slf4j
@Component
public class StockNameIndexRefreshJob implements ApplicationRunner {

    private final StockNameRefreshService stockNameRefreshService;
    private final StockNameIndexHolder stockNameIndexHolder;
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);

    public StockNameIndexRefreshJob(StockNameRefreshService stockNameRefreshService,
                                    StockNameIndexHolder stockNameIndexHolder) {
        this.stockNameRefreshService = stockNameRefreshService;
        this.stockNameIndexHolder = stockNameIndexHolder;
    }

    @Override
    public void run(ApplicationArguments args) {
        refreshOnce("startup");
    }

    @Scheduled(
            cron = "${spring.ai.trading.stock-name-index.refresh-cron:0 30 3 * * ?}",
            zone = "${spring.ai.trading.stock-name-index.refresh-zone:Asia/Shanghai}")
    public void runScheduledRefresh() {
        refreshOnce("scheduled");
    }

    boolean refreshOnce(String trigger) {
        if (!refreshRunning.compareAndSet(false, true)) {
            log.info("Skip stock name index refresh because another refresh is running: trigger={}", trigger);
            return false;
        }

        try {
            long startedAt = System.nanoTime();
            StockNameRefreshService.RefreshResult result = stockNameRefreshService.refresh();
            long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (result.success()) {
                log.info("Stock name index refresh succeeded: trigger={}, status={}, loadedAt={}, expiresAt={}, records={}, latencyMs={}",
                        trigger,
                        stockNameIndexHolder.currentStatus(),
                        result.publishedIndex().getLoadedAt(),
                        result.publishedIndex().getExpiresAt(),
                        result.publishedIndex().getRecords().size(),
                        elapsedMs);
            } else {
                log.warn("Stock name index refresh failed: trigger={}, status={}, attemptedAt={}, error={}, latencyMs={}",
                        trigger,
                        stockNameIndexHolder.currentStatus(),
                        result.attemptedAt(),
                        result.error() != null ? result.error().getMessage() : "unknown",
                        elapsedMs);
            }
            return result.success();
        } catch (RuntimeException e) {
            log.warn("Stock name index refresh raised an unexpected error: trigger={}, error={}",
                    trigger, e.getMessage(), e);
            return false;
        } finally {
            refreshRunning.set(false);
        }
    }
}
