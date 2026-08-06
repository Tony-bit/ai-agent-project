package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Rebuilds and atomically publishes the local stock name index.
 */
public class StockNameRefreshService {

    private final StockNameSource stockNameSource;
    private final StockNameIndexHolder indexHolder;
    private final Clock clock;
    private final Duration ttl;
    private final StockNameMetrics stockNameMetrics;

    public StockNameRefreshService(StockNameSource stockNameSource,
                                   StockNameIndexHolder indexHolder,
                                   Clock clock,
                                   Duration ttl) {
        this(stockNameSource, indexHolder, clock, ttl, new StockNameMetrics());
    }

    public StockNameRefreshService(StockNameSource stockNameSource,
                                   StockNameIndexHolder indexHolder,
                                   Clock clock,
                                   Duration ttl,
                                   StockNameMetrics stockNameMetrics) {
        this.stockNameSource = Objects.requireNonNull(stockNameSource, "stockNameSource");
        this.indexHolder = Objects.requireNonNull(indexHolder, "indexHolder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.stockNameMetrics = Objects.requireNonNull(stockNameMetrics, "stockNameMetrics");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be greater than 0");
        }
    }

    public RefreshResult refresh() {
        Instant loadedAt = clock.instant();
        Instant finishedAt = loadedAt;
        try {
            List<denny.ai.agent.domain.model.valobj.stock.StockNameRecord> records =
                    stockNameSource.loadActiveStockNames();
            if (records.isEmpty()) {
                throw new IllegalStateException("stock name directory is empty");
            }
            StockNameIndex newIndex = StockNameIndex.of(records, loadedAt, loadedAt.plus(ttl));
            indexHolder.publish(newIndex);
            finishedAt = clock.instant();
            stockNameMetrics.recordRefreshSuccess(newIndex, Duration.between(loadedAt, finishedAt));
            return RefreshResult.success(newIndex);
        } catch (RuntimeException e) {
            finishedAt = clock.instant();
            stockNameMetrics.recordRefreshFailure(indexHolder.currentStatus(), Duration.between(loadedAt, finishedAt), e);
            return RefreshResult.failure(loadedAt, e);
        }
    }

    public record RefreshResult(boolean success,
                                Instant attemptedAt,
                                StockNameIndex publishedIndex,
                                RuntimeException error) {

        static RefreshResult success(StockNameIndex publishedIndex) {
            return new RefreshResult(true, publishedIndex.getLoadedAt(), publishedIndex, null);
        }

        static RefreshResult failure(Instant attemptedAt, RuntimeException error) {
            return new RefreshResult(false, attemptedAt, null, error);
        }
    }
}
