package denny.ai.agent.trading.domain.cleanup;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void dryRunReportsInventoryWithoutExecutingCleanup() {
        AtomicInteger executions = new AtomicInteger();
        TradingCleanupInventory inventory = inventory(false, false, false, false,
                5,
                new TradingCleanupRuntimeInventory(2, 3, 4, 13, 14),
                new TradingCleanupRuntimeInventory(15, 16, 17, 18, 19));
        TradingCleanupService service = service(inventory, (batchId, inspected) -> {
            executions.incrementAndGet();
            return 99;
        });

        TradingCleanupReport report = service.dryRun();

        assertEquals("DRY_RUN", report.mode());
        assertEquals(NOW, report.generatedAt());
        assertFalse(report.executable());
        assertEquals(0, executions.get());
        assertEquals(5, report.inventory().legacyCodeReferences());
        assertEquals(6, report.inventory().prompts().v1Active());
        assertEquals(7, report.inventory().prompts().v1Archived());
        assertEquals(8, report.inventory().prompts().v2Active());
        assertEquals(9, report.inventory().prompts().v2Archived());
        assertEquals(10, report.inventory().prompts().v3Active());
        assertEquals(11, report.inventory().prompts().v3Archived());
        assertEquals(12, report.inventory().prompts().duplicates());
        assertEquals(13, report.inventory().prompts().orphans());
        assertEquals(13, report.inventory().strictV2Runtime().caches());
        assertEquals(14, report.inventory().strictV2Runtime().dualWriteRecords());
        assertEquals(15, report.inventory().relaxedV3Runtime().activeRuns());
        assertEquals(19, report.inventory().relaxedV3Runtime().dualWriteRecords());
        assertEquals(10, report.inventory().expiredV2Caches());
        assertEquals(11, report.inventory().expiredV2Snapshots());
        assertEquals(12, report.inventory().shadowRunDetails());
        assertEquals(20, report.inventory().disposition().plannedDeletions());
        assertEquals(21, report.inventory().disposition().plannedArchives());
        assertEquals(22, report.inventory().disposition().plannedRetentions());
        assertEquals("1000..2000", report.inventory().disposition().primaryKeyRange());
        assertEquals("backup://trading/cleanup", report.inventory().disposition().backupLocation());
        assertTrue(report.blockedReasons().contains("acceptance is incomplete"));
        assertTrue(report.blockedReasons().contains("active STRICT_V2 runs remain"));
        assertEquals(null, report.approvedBatchId());
        assertEquals(0, report.deletedObjects());
    }

    @Test
    void executeRejectsMissingApprovalBatchWithoutInspectingOrExecuting() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        TradingCleanupService service = new TradingCleanupService(
                () -> {
                    inspections.incrementAndGet();
                    return readyInventory();
                },
                (batchId, inventory) -> {
                    executions.incrementAndGet();
                    return 1;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> service.execute("  "));
        assertEquals(0, inspections.get());
        assertEquals(0, executions.get());
    }

    @Test
    void executeRejectsWhenAnyCleanupGateIsBlocked() {
        AtomicInteger executions = new AtomicInteger();
        TradingCleanupInventory blocked = inventory(true, true, true, true,
                0,
                new TradingCleanupRuntimeInventory(0, 1, 0, 0, 0),
                new TradingCleanupRuntimeInventory(0, 0, 0, 0, 0));
        TradingCleanupService service = service(blocked, (batchId, inventory) -> {
            executions.incrementAndGet();
            return 1;
        });

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service.execute("cleanup-20260728"));

        assertTrue(error.getMessage().contains("STRICT_V2 retries remain"));
        assertEquals(0, executions.get());
    }

    @Test
    void executeRunsApprovedBatchExactlyOnceAfterAllGatesPass() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<String> batch = new AtomicReference<>();
        AtomicReference<TradingCleanupInventory> receivedInventory = new AtomicReference<>();
        TradingCleanupInventory inventory = readyInventory();
        TradingCleanupService service = service(inventory, (batchId, inspected) -> {
            executions.incrementAndGet();
            batch.set(batchId);
            receivedInventory.set(inspected);
            return 37;
        });

        TradingCleanupReport report = service.execute("  cleanup-20260728  ");

        assertEquals(1, executions.get());
        assertEquals("cleanup-20260728", batch.get());
        assertEquals(inventory, receivedInventory.get());
        assertEquals("EXECUTE", report.mode());
        assertEquals("cleanup-20260728", report.approvedBatchId());
        assertEquals(37, report.deletedObjects());
        assertTrue(report.executable());
        assertTrue(report.blockedReasons().isEmpty());
    }

    private TradingCleanupService service(TradingCleanupInventory inventory,
                                          TradingCleanupExecutor executor) {
        return new TradingCleanupService(() -> inventory, executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TradingCleanupInventory readyInventory() {
        return inventory(true, true, true, true,
                0,
                new TradingCleanupRuntimeInventory(0, 0, 0, 0, 0),
                new TradingCleanupRuntimeInventory(0, 0, 0, 2, 0));
    }

    private TradingCleanupInventory inventory(boolean acceptancePassed,
                                               boolean stableObservationWindowPassed,
                                               boolean rollbackWindowClosed,
                                               boolean allConsumersMigrated,
                                               long legacyCodeReferences,
                                               TradingCleanupRuntimeInventory strictV2Runtime,
                                               TradingCleanupRuntimeInventory relaxedV3Runtime) {
        return new TradingCleanupInventory(
                acceptancePassed,
                stableObservationWindowPassed,
                rollbackWindowClosed,
                allConsumersMigrated,
                legacyCodeReferences,
                new TradingCleanupPromptInventory(6, 7, 8, 9, 10, 11, 12, 13),
                strictV2Runtime,
                relaxedV3Runtime,
                10,
                11,
                12,
                new TradingCleanupDisposition(20, 21, 22,
                        "1000..2000", "backup://trading/cleanup"));
    }
}
