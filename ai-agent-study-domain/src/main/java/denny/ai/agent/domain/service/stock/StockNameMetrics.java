package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameIndexStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional Micrometer metrics for stock-name index refresh, resolution, and pending lifecycle.
 */
public class StockNameMetrics {

    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final AtomicInteger indexStatusCode = new AtomicInteger(0);
    private final AtomicInteger indexRecordCount = new AtomicInteger(0);
    private final AtomicLong indexLastRefreshEpochSeconds = new AtomicLong(0);
    private final ConcurrentMap<String, Counter> pendingCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> resolutionCounters = new ConcurrentHashMap<>();

    public StockNameMetrics() {
        this(null);
    }

    public StockNameMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.enabled = meterRegistry != null;
        if (enabled) {
            meterRegistry.gauge("stock_name_index_status_code", indexStatusCode);
            meterRegistry.gauge("stock_name_index_records", indexRecordCount);
            meterRegistry.gauge("stock_name_index_last_refresh_epoch_seconds", indexLastRefreshEpochSeconds);
        }
    }

    public void registerIndexHolder(StockNameIndexHolder holder) {
        Objects.requireNonNull(holder, "holder");
        if (!enabled) {
            return;
        }
        meterRegistry.gauge("stock_name_index_age_seconds", holder,
                it -> currentAgeSeconds(it.currentIndex().orElse(null)));
        refreshIndexGauges(holder.currentStatus(), holder.currentIndex().orElse(null));
    }

    public void recordRefreshSuccess(StockNameIndex index, Duration duration) {
        refreshIndexGauges(StockNameIndexStatus.READY, index);
        if (!enabled) {
            return;
        }
        Timer.builder("stock_name_index_refresh_latency")
                .tag("outcome", "success")
                .register(meterRegistry)
                .record(duration);
        Counter.builder("stock_name_index_refresh_total")
                .tag("outcome", "success")
                .register(meterRegistry)
                .increment();
    }

    public void recordRefreshFailure(StockNameIndexStatus status, Duration duration, RuntimeException error) {
        refreshIndexGauges(status, null);
        if (!enabled) {
            return;
        }
        Timer.builder("stock_name_index_refresh_latency")
                .tag("outcome", "failure")
                .register(meterRegistry)
                .record(duration);
        Counter.builder("stock_name_index_refresh_total")
                .tag("outcome", "failure")
                .tag("error_type", error == null ? "unknown" : error.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
    }

    public void recordResolution(String status, int candidateCount, Duration duration) {
        if (!enabled) {
            return;
        }
        resolutionCounters.computeIfAbsent(status, key ->
                Counter.builder("stock_name_resolution_total")
                        .tag("status", key)
                        .register(meterRegistry)).increment();
        Timer.builder("stock_name_resolution_latency")
                .tag("status", status)
                .register(meterRegistry)
                .record(duration);
        DistributionSummary.builder("stock_name_resolution_candidates")
                .tag("status", status)
                .register(meterRegistry)
                .record(candidateCount);
    }

    public void recordPendingLookup(boolean hit) {
        recordPendingLifecycle(hit ? "lookup_hit" : "lookup_miss", true);
    }

    public void recordPendingLifecycle(String operation, boolean success) {
        if (!enabled) {
            return;
        }
        String outcome = success ? "success" : "rejected";
        pendingCounters.computeIfAbsent(operation + ":" + outcome, key ->
                Counter.builder("stock_resolution_pending_total")
                        .tag("operation", operation)
                        .tag("outcome", outcome)
                        .register(meterRegistry)).increment();
    }

    private void refreshIndexGauges(StockNameIndexStatus status, StockNameIndex index) {
        indexStatusCode.set(toStatusCode(status));
        indexRecordCount.set(index == null ? 0 : index.getRecords().size());
        indexLastRefreshEpochSeconds.set(index == null || index.getLoadedAt() == null ? 0 : index.getLoadedAt().getEpochSecond());
    }

    private double currentAgeSeconds(StockNameIndex index) {
        if (index == null || index.getLoadedAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(index.getLoadedAt(), Instant.now()).getSeconds());
    }

    private int toStatusCode(StockNameIndexStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case NOT_READY -> 0;
            case READY -> 1;
            case EXPIRED -> 2;
        };
    }
}
