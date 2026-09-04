package com.agentic.orchestrator.api;

import com.agentic.orchestrator.execution.NodeExecution;
import com.agentic.orchestrator.execution.NodeState;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.plan.Task;
import java.time.Instant;
import java.util.List;

/**
 * A point-in-time view of a run.
 *
 * <p>A snapshot rather than the live objects: the scheduler mutates node state concurrently, and
 * serialising those objects directly would race with it. Copying under no lock is safe here because
 * every mutated field is volatile — a snapshot may be a few milliseconds stale, never torn.
 */
public record RunResponse(
        String id,
        String planId,
        String scenarioType,
        String autonomyLevel,
        String status,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        boolean paused,
        String stopReason,
        Counts counts,
        List<NodeView> nodes) {

    public static RunResponse of(Run run) {
        List<NodeView> nodes = run.nodes().stream()
                .map(node -> NodeView.of(run.task(node.taskId()), node))
                .toList();

        return new RunResponse(
                run.id(),
                run.plan().id(),
                run.plan().requirement().scenarioType().name(),
                run.autonomyLevel().name(),
                run.status().name(),
                run.createdAt(),
                run.startedAt(),
                run.endedAt(),
                run.durationMs(),
                run.paused(),
                run.stopReason(),
                Counts.of(run),
                nodes);
    }

    public record Counts(
            int total, long pending, long running, long awaitingApproval, long retrying,
            long succeeded, long degraded, long failed, long blocked, long cancelled,
            long rolledBack) {

        static Counts of(Run run) {
            return new Counts(
                    run.nodes().size(),
                    run.countInState(NodeState.PENDING),
                    run.countInState(NodeState.RUNNING),
                    run.countInState(NodeState.AWAITING_APPROVAL),
                    run.countInState(NodeState.RETRYING),
                    run.countInState(NodeState.SUCCEEDED),
                    // Surfaced separately from succeeded: a green run with degraded nodes is not
                    // the same thing as a green run, and a reviewer should not have to dig for that.
                    run.nodes().stream().filter(NodeExecution::completedDegraded).count(),
                    run.countInState(NodeState.FAILED),
                    run.countInState(NodeState.BLOCKED),
                    run.countInState(NodeState.CANCELLED),
                    run.countInState(NodeState.ROLLED_BACK));
        }
    }

    public record NodeView(
            String taskId,
            String title,
            String stage,
            String agentRole,
            String blastRadius,
            String state,
            int attempt,
            Instant startedAt,
            Instant endedAt,
            Long durationMs,
            boolean completedDegraded,
            String message) {

        static NodeView of(Task task, NodeExecution node) {
            return new NodeView(
                    node.taskId(),
                    task == null ? node.taskId() : task.title(),
                    task == null ? null : task.stage().name(),
                    task == null ? null : task.agentRole().name(),
                    task == null ? null : task.blastRadius().name(),
                    node.state().name(),
                    node.attempt(),
                    node.startedAt(),
                    node.endedAt(),
                    node.durationMs(),
                    node.completedDegraded(),
                    node.message());
        }
    }
}
