package com.agentic.orchestrator.metrics;

import java.util.List;

/**
 * A computed view of how the orchestrator is behaving (CR4.10).
 *
 * <p>Rates are {@code null} rather than {@code 0.0} when their denominator is empty. A success rate
 * of "no runs have finished" is not a success rate of zero, and a dashboard that cannot tell those
 * apart shows a red 0% for a system that has done nothing wrong.
 */
public record MetricsSnapshot(
        RunMetrics runs,
        NodeMetrics nodes,
        ReliabilityMetrics reliability,
        LatencyMetrics latency,
        GovernanceMetrics governance,
        List<StageLatency> stages) {

    public static MetricsSnapshot empty() {
        return new MetricsSnapshot(
                new RunMetrics(0, 0, 0, 0, null),
                new NodeMetrics(0, 0, 0, 0, 0, null),
                new ReliabilityMetrics(0, null, 0, 0, 0, 0, null, 0, 0, null, 0, 0),
                new LatencyMetrics(null, null, null, null, null, null),
                new GovernanceMetrics(0, 0, 0, 0, null, null, 0, 0),
                List.of());
    }

    /** @param successRatePct completed / terminal — runs still in flight are excluded */
    public record RunMetrics(long total, long active, long completed, long failed,
                             Double successRatePct) { }

    /** @param attempted nodes that were actually dispatched; blocked nodes are not failures */
    public record NodeMetrics(long attempted, long succeeded, long degraded, long failed,
                              long blocked, Double successRatePct) { }

    /**
     * @param meanTimeToRecoveryMs first failure of a node to its eventual success
     * @param unrecoveredFailures  nodes that failed and never came back; excluded from the mean
     */
    public record ReliabilityMetrics(
            long retriesScheduled,
            Double retryRatePct,
            long retriesExhausted,
            long fallbacksAttempted,
            long timeouts,
            long nodesRolledBack,
            Double rollbackRatePct,
            long compensationFailures,
            long plansRevised,
            Long meanTimeToRecoveryMs,
            int recoveredFailures,
            int unrecoveredFailures) { }

    public record LatencyMetrics(
            Long meanRunMs, Long p95RunMs, Long maxRunMs,
            Long meanNodeMs, Long p95NodeMs, Long maxNodeMs) { }

    /**
     * @param meanWaitMs how long humans took to answer — deliberately not folded into run latency,
     *                   since time parked on a person is not the orchestrator being slow
     */
    public record GovernanceMetrics(
            long approvalsRequested, long approved, long rejected, long stillPending,
            Long meanWaitMs, Long maxWaitMs, long policyDenials, long gateFailures) { }

    public record StageLatency(String stage, int samples, Long meanMs, Long maxMs, long totalMs) { }
}
