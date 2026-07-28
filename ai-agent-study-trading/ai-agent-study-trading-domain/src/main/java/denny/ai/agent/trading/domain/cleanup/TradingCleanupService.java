package denny.ai.agent.trading.domain.cleanup;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TradingCleanupService {

    private final TradingCleanupInventoryProvider inventoryProvider;
    private final TradingCleanupExecutor executor;
    private final Clock clock;

    public TradingCleanupService(TradingCleanupInventoryProvider inventoryProvider,
                                 TradingCleanupExecutor executor) {
        this(inventoryProvider, executor, Clock.systemUTC());
    }

    TradingCleanupService(TradingCleanupInventoryProvider inventoryProvider,
                          TradingCleanupExecutor executor,
                          Clock clock) {
        this.inventoryProvider = Objects.requireNonNull(inventoryProvider, "inventoryProvider");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TradingCleanupReport dryRun() {
        TradingCleanupInventory inventory = inventoryProvider.inspect();
        return report("DRY_RUN", inventory, blockedReasons(inventory), null, 0);
    }

    public TradingCleanupReport execute(String approvedBatchId) {
        if (approvedBatchId == null || approvedBatchId.isBlank()) {
            throw new IllegalArgumentException("approved batch id is required for cleanup execution");
        }
        TradingCleanupInventory inventory = inventoryProvider.inspect();
        List<String> blocked = blockedReasons(inventory);
        if (!blocked.isEmpty()) {
            throw new IllegalStateException("cleanup gates are not satisfied: " + String.join("; ", blocked));
        }
        long deleted = executor.executeApprovedBatch(approvedBatchId.trim(), inventory);
        return report("EXECUTE", inventory, List.of(), approvedBatchId.trim(), deleted);
    }

    private TradingCleanupReport report(String mode,
                                        TradingCleanupInventory inventory,
                                        List<String> blocked,
                                        String batchId,
                                        long deleted) {
        return new TradingCleanupReport(mode, Instant.now(clock), inventory,
                blocked.isEmpty(), blocked, batchId, deleted);
    }

    private List<String> blockedReasons(TradingCleanupInventory inventory) {
        List<String> reasons = new ArrayList<>();
        if (!inventory.acceptancePassed()) reasons.add("acceptance is incomplete");
        if (!inventory.stableObservationWindowPassed()) reasons.add("stable observation window is incomplete");
        if (!inventory.rollbackWindowClosed()) reasons.add("V2 rollback window is open");
        if (!inventory.allConsumersMigrated()) reasons.add("consumer migration is incomplete");
        if (inventory.strictV2Runtime().activeRuns() != 0) reasons.add("active STRICT_V2 runs remain");
        if (inventory.strictV2Runtime().retries() != 0) reasons.add("STRICT_V2 retries remain");
        if (inventory.strictV2Runtime().recoverableSnapshots() != 0) {
            reasons.add("recoverable STRICT_V2 snapshots remain");
        }
        return List.copyOf(reasons);
    }
}
