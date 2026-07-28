package denny.ai.agent.trading.domain.metrics;

import denny.ai.agent.trading.api.metrics.TradingRolloutMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingRolloutMonitorTest {

    @Test
    void aggregatesRatesNeededByV3RolloutDashboard() {
        TradingRolloutMonitor monitor = new TradingRolloutMonitor();
        monitor.recordNode("VALID");
        monitor.recordNode("EXECUTION_FAILED");
        monitor.recordSafeFallback();
        monitor.recordSignalComparison(false);
        monitor.recordSignalComparison(true);
        monitor.recordToolTargetOverride();
        monitor.recordIdentityBoundaryViolation();

        TradingRolloutMonitor.Snapshot snapshot = monitor.snapshot();

        assertEquals(2, snapshot.nodeResults());
        assertEquals(0.5, snapshot.degradationRate());
        assertEquals(0.5, snapshot.safeFallbackRate());
        assertEquals(0.5, snapshot.signalDifferenceRate());
        assertEquals(1, snapshot.toolTargetOverrides());
        assertEquals(1, snapshot.identityBoundaryViolations());
    }
}
