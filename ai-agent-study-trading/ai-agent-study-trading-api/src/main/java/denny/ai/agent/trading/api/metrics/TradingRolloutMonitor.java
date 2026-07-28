package denny.ai.agent.trading.api.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

@Component
public class TradingRolloutMonitor {

    private final LongAdder nodeResults = new LongAdder();
    private final LongAdder degradedNodes = new LongAdder();
    private final LongAdder safeFallbacks = new LongAdder();
    private final LongAdder signalComparisons = new LongAdder();
    private final LongAdder signalDifferences = new LongAdder();
    private final LongAdder toolTargetOverrides = new LongAdder();
    private final LongAdder identityBoundaryViolations = new LongAdder();

    public void recordNode(String status) {
        nodeResults.increment();
        if ("DEGRADED".equals(status) || "EXECUTION_FAILED".equals(status)) {
            degradedNodes.increment();
        }
        if ("SAFE_FALLBACK".equals(status)) {
            safeFallbacks.increment();
        }
    }

    public void recordSignalComparison(boolean different) {
        signalComparisons.increment();
        if (different) signalDifferences.increment();
    }

    public void recordToolTargetOverride() { toolTargetOverrides.increment(); }
    public void recordIdentityBoundaryViolation() { identityBoundaryViolations.increment(); }
    public void recordSafeFallback() { safeFallbacks.increment(); }

    public Snapshot snapshot() {
        long nodes = nodeResults.sum();
        long comparisons = signalComparisons.sum();
        return new Snapshot(nodes, degradedNodes.sum(), rate(degradedNodes.sum(), nodes),
                safeFallbacks.sum(), rate(safeFallbacks.sum(), nodes), comparisons,
                signalDifferences.sum(), rate(signalDifferences.sum(), comparisons),
                toolTargetOverrides.sum(), identityBoundaryViolations.sum());
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    public record Snapshot(long nodeResults, long degradedNodes, double degradationRate,
                           long safeFallbacks, double safeFallbackRate,
                           long signalComparisons, long signalDifferences, double signalDifferenceRate,
                           long toolTargetOverrides, long identityBoundaryViolations) {
    }
}
