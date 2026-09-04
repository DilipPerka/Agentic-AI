package com.agentic.orchestrator.metrics;

import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.EventType;
import com.agentic.orchestrator.execution.NodeExecution;
import com.agentic.orchestrator.execution.NodeState;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunEvent;
import com.agentic.orchestrator.execution.RunRepository;
import com.agentic.orchestrator.execution.RunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Reliability metrics (CR4.10), derived rather than counted.
 *
 * <p>Everything here is computed from the runs and the append-only event log at request time. No
 * counters are incremented as the system runs, which matters for two reasons: a counter that drifts
 * from the log is worse than no counter, and metrics computed from the audit trail are metrics a
 * reviewer can re-derive by hand from the same events. The cost is O(events) per request, which for
 * a demo-scale system is nothing.
 *
 * <p>The distinction the numbers are careful about is <em>attempted</em> versus <em>reached</em>. A
 * node that never ran because its predecessor failed did not fail; counting it as a failure would
 * make one bad node look like a collapse, and would let a run that blocked early look better than
 * one that fought through.
 */
@Service
public class MetricsService {

    private final RunRepository runs;
    private final EventLog events;

    public MetricsService(RunRepository runs, EventLog events) {
        this.runs = runs;
        this.events = events;
    }

    public MetricsSnapshot overall() {
        return compute(runs.findAll());
    }

    /** Metrics for a single run, so the console can show a strip without loading the fleet. */
    public MetricsSnapshot forRun(String runId) {
        return runs.findById(runId)
                .map(run -> compute(List.of(run)))
                .orElseGet(() -> compute(List.of()));
    }

    private MetricsSnapshot compute(List<Run> scope) {
        if (scope.isEmpty()) {
            return MetricsSnapshot.empty();
        }

        List<RunEvent> log = new ArrayList<>();
        for (Run run : scope) {
            log.addAll(events.forRun(run.id()));
        }

        return new MetricsSnapshot(
                runCounts(scope),
                nodeCounts(scope),
                reliability(scope, log),
                latency(scope),
                governance(log),
                perStageLatency(scope));
    }

    // ------------------------------------------------------------------ runs

    private MetricsSnapshot.RunMetrics runCounts(List<Run> scope) {
        long total = scope.size();
        long completed = scope.stream().filter(r -> r.status() == RunStatus.COMPLETED).count();
        long failed = scope.stream().filter(r -> r.status() == RunStatus.FAILED).count();
        long terminal = scope.stream().filter(r -> r.status().isTerminal()).count();
        long active = total - terminal;

        // Denominator is terminal runs, not all runs. A run still in flight has not succeeded or
        // failed yet, and counting it either way moves the number for a reason that is not a result.
        return new MetricsSnapshot.RunMetrics(total, active, completed, failed,
                percentage(completed, terminal));
    }

    // ------------------------------------------------------------------ nodes

    private MetricsSnapshot.NodeMetrics nodeCounts(List<Run> scope) {
        long attempted = 0;
        long succeeded = 0;
        long degraded = 0;
        long failed = 0;
        long blocked = 0;

        for (Run run : scope) {
            for (NodeExecution node : run.nodes()) {
                switch (node.state()) {
                    case BLOCKED, CANCELLED -> blocked++;
                    case SUCCEEDED -> {
                        attempted++;
                        succeeded++;
                        if (node.completedDegraded()) {
                            degraded++;
                        }
                    }
                    // Counted by outcome, not by attempt count. A node refused at its entry gate —
                    // rejected by a human, or denied by policy — never ran, so its attempt is 0, but
                    // it is unambiguously a failure. Skipping it on attempt count would make a
                    // rejected node vanish from the numbers entirely.
                    case FAILED -> {
                        attempted++;
                        failed++;
                    }
                    // Still in flight or not yet reached: no outcome to record either way.
                    default -> {
                        if (node.attempt() > 0) {
                            attempted++;
                        }
                    }
                }
            }
        }

        return new MetricsSnapshot.NodeMetrics(attempted, succeeded, degraded, failed, blocked,
                percentage(succeeded, attempted));
    }

    // ------------------------------------------------------------------ reliability

    private MetricsSnapshot.ReliabilityMetrics reliability(List<Run> scope, List<RunEvent> log) {
        long dispatched = count(log, EventType.NODE_STARTED);
        long retries = count(log, EventType.RETRY_SCHEDULED);
        long exhausted = count(log, EventType.RETRIES_EXHAUSTED);
        long fallbacks = count(log, EventType.FALLBACK_ATTEMPTED);
        long timeouts = count(log, EventType.NODE_TIMED_OUT);
        long rolledBack = count(log, EventType.NODE_ROLLED_BACK);
        long compensationFailures = count(log, EventType.COMPENSATION_FAILED);
        long replans = count(log, EventType.PLAN_REVISED);

        Recovery recovery = meanTimeToRecovery(log);

        return new MetricsSnapshot.ReliabilityMetrics(
                retries,
                percentage(retries, dispatched),
                exhausted,
                fallbacks,
                timeouts,
                rolledBack,
                percentage(rolledBack, dispatched),
                compensationFailures,
                replans,
                recovery.meanMs(),
                recovery.recovered(),
                recovery.unrecovered());
    }

    /**
     * Mean time to recovery: first failure of a node to the success that followed it.
     *
     * <p>Measured per node rather than per run, because that is the interval the retry and fallback
     * machinery actually governs. Nodes that failed and never recovered are excluded from the mean
     * and reported separately — averaging them in as zero would flatter the number, and treating
     * them as infinite would destroy it. Both are lies; the honest answer is two numbers.
     */
    private Recovery meanTimeToRecovery(List<RunEvent> log) {
        Map<String, Instant> firstFailure = new HashMap<>();
        List<Long> recoveries = new ArrayList<>();

        for (RunEvent event : log.stream()
                .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                .toList()) {

            if (event.taskId() == null) {
                continue;
            }
            String key = event.runId() + "/" + event.taskId();

            switch (event.type()) {
                case NODE_FAILED, NODE_TIMED_OUT, RETRY_SCHEDULED ->
                        firstFailure.putIfAbsent(key, event.timestamp());
                case NODE_SUCCEEDED -> {
                    Instant failedAt = firstFailure.remove(key);
                    if (failedAt != null) {
                        recoveries.add(Duration.between(failedAt, event.timestamp()).toMillis());
                    }
                }
                default -> { }
            }
        }

        Long mean = recoveries.isEmpty() ? null
                : Math.round(recoveries.stream().mapToLong(Long::longValue).average().orElse(0));

        // Whatever is left in the map failed and never came back.
        return new Recovery(mean, recoveries.size(), firstFailure.size());
    }

    private record Recovery(Long meanMs, int recovered, int unrecovered) { }

    // ------------------------------------------------------------------ latency

    private MetricsSnapshot.LatencyMetrics latency(List<Run> scope) {
        List<Long> runDurations = scope.stream()
                .filter(run -> run.status().isTerminal())
                .map(Run::durationMs)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();

        List<Long> nodeDurations = scope.stream()
                .flatMap(run -> run.nodes().stream())
                .map(NodeExecution::durationMs)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();

        return new MetricsSnapshot.LatencyMetrics(
                mean(runDurations), percentile(runDurations, 95), max(runDurations),
                mean(nodeDurations), percentile(nodeDurations, 95), max(nodeDurations));
    }

    /**
     * Per-stage node latency, so "where does the time go" has an answer.
     *
     * <p>Sorted by total time rather than by name: the stage a reviewer should look at first is the
     * one consuming the most wall-clock, not the one starting with 'A'.
     */
    private List<MetricsSnapshot.StageLatency> perStageLatency(List<Run> scope) {
        Map<String, List<Long>> byStage = new TreeMap<>();

        for (Run run : scope) {
            for (NodeExecution node : run.nodes()) {
                Long duration = node.durationMs();
                if (duration == null) {
                    continue;
                }
                var task = run.task(node.taskId());
                String stage = task == null ? "UNKNOWN" : task.stage().name();
                byStage.computeIfAbsent(stage, key -> new ArrayList<>()).add(duration);
            }
        }

        return byStage.entrySet().stream()
                .map(entry -> {
                    List<Long> sorted = entry.getValue().stream().sorted().toList();
                    long total = sorted.stream().mapToLong(Long::longValue).sum();
                    return new MetricsSnapshot.StageLatency(entry.getKey(), sorted.size(),
                            mean(sorted), max(sorted), total);
                })
                .sorted((a, b) -> Long.compare(b.totalMs(), a.totalMs()))
                .toList();
    }

    // ------------------------------------------------------------------ governance

    /**
     * How much the humans were asked, and how long they took.
     *
     * <p>Approval wait time is separated from execution latency throughout. Time a run spent parked
     * on a person is not the orchestrator being slow, and folding the two together would make the
     * governance features look like a performance problem.
     */
    private MetricsSnapshot.GovernanceMetrics governance(List<RunEvent> log) {
        Map<String, Instant> requested = new HashMap<>();
        List<Long> waits = new ArrayList<>();
        long granted = 0;
        long rejected = 0;

        for (RunEvent event : log.stream()
                .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                .toList()) {

            String key = event.runId() + "/" + event.taskId();
            switch (event.type()) {
                case APPROVAL_REQUESTED -> requested.put(key, event.timestamp());
                case APPROVAL_GRANTED, APPROVAL_REJECTED -> {
                    if (event.type() == EventType.APPROVAL_GRANTED) {
                        granted++;
                    } else {
                        rejected++;
                    }
                    Instant askedAt = requested.remove(key);
                    if (askedAt != null) {
                        waits.add(Duration.between(askedAt, event.timestamp()).toMillis());
                    }
                }
                default -> { }
            }
        }

        return new MetricsSnapshot.GovernanceMetrics(
                granted + rejected + requested.size(),
                granted,
                rejected,
                requested.size(),
                mean(waits),
                max(waits),
                count(log, EventType.POLICY_DENIED),
                count(log, EventType.GATE_FAILED));
    }

    // ------------------------------------------------------------------ helpers

    private static long count(List<RunEvent> log, EventType type) {
        return log.stream().filter(event -> event.type() == type).count();
    }

    /** Null rather than 0 when there is nothing to divide: "no data" is not "zero percent". */
    private static Double percentage(long numerator, long denominator) {
        return denominator == 0 ? null
                : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    private static Long mean(List<Long> values) {
        return values.isEmpty() ? null
                : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static Long max(List<Long> values) {
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** Nearest-rank percentile over an already-sorted list. */
    private static Long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
    }
}
