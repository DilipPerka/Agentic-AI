package com.agentic.orchestrator.execution;

import java.time.Instant;

/**
 * One entry in a run's append-only log.
 *
 * @param seq    monotonic per run; lets a consumer resume from a known point rather than re-reading
 *               the whole log, which is what a live UI feed will need
 * @param actor  who caused this — an agent role, {@code SCHEDULER}, or a human id
 */
public record RunEvent(
        long seq,
        String runId,
        String taskId,
        EventType type,
        String actor,
        String message,
        Instant timestamp) {
}
