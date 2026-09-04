package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.execution.NodeState;
import com.agentic.orchestrator.execution.RunStatus;
import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.replan.ReplanTrigger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A run as it exists on disk.
 *
 * <p>A separate shape from the live {@code Run} on purpose. The live object owns a lock and mutable
 * node state; this is a flat, immutable row set. Keeping them distinct is what allows the domain to
 * stay free of persistence concerns.
 */
public record PersistedRun(
        String id,
        String planId,
        AutonomyLevel autonomyLevel,
        RunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        boolean stopRequested,
        String stopReason,
        boolean paused,
        int replanCount,
        String pendingReplanGuidance,
        ReplanTrigger pendingReplanTrigger,
        String baseRunId,
        List<PersistedNode> nodes) {

    public record PersistedNode(
            String taskId,
            NodeState state,
            int attempt,
            Instant startedAt,
            Instant endedAt,
            String message,
            Map<String, String> outputs,
            boolean fallbackAttempted,
            boolean completedDegraded,
            String fingerprint) {
    }
}
