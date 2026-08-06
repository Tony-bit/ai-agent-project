package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameIndexStatus;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the latest published stock name index for the current JVM.
 */
public class StockNameIndexHolder {

    private final AtomicReference<StockNameIndex> indexRef = new AtomicReference<>();
    private final Clock clock;

    public StockNameIndexHolder(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StockNameIndexStatus currentStatus() {
        StockNameIndex index = indexRef.get();
        if (index == null) {
            return StockNameIndexStatus.NOT_READY;
        }
        if (clock.instant().isBefore(index.getExpiresAt())) {
            return StockNameIndexStatus.READY;
        }
        return StockNameIndexStatus.EXPIRED;
    }

    public Optional<StockNameIndex> currentIndex() {
        return Optional.ofNullable(indexRef.get());
    }

    public Optional<StockNameIndex> readyIndex() {
        if (currentStatus() != StockNameIndexStatus.READY) {
            return Optional.empty();
        }
        return currentIndex();
    }

    public void publish(StockNameIndex index) {
        Objects.requireNonNull(index, "index");
        if (index.getLoadedAt() == null || index.getExpiresAt() == null) {
            throw new IllegalArgumentException("published index must include loadedAt and expiresAt");
        }
        indexRef.set(index);
    }
}
