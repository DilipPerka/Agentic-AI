package com.agentic.orchestrator.api;

import com.agentic.orchestrator.governance.ApprovalRequest;
import java.time.Instant;
import java.util.List;

/**
 * What an approver sees. Deliberately includes {@code reasons} and {@code policyIds}: a queue that
 * shows only task ids trains people to click approve without reading.
 */
public record ApprovalView(
        String id,
        String runId,
        String taskId,
        String taskTitle,
        String blastRadius,
        String status,
        List<String> reasons,
        List<String> policyIds,
        Instant requestedAt,
        String decidedBy,
        Instant decidedAt,
        String guidance) {

    public static ApprovalView of(ApprovalRequest request) {
        return new ApprovalView(
                request.id(),
                request.runId(),
                request.taskId(),
                request.taskTitle(),
                request.blastRadius().name(),
                request.status().name(),
                request.reasons(),
                request.policyIds(),
                request.requestedAt(),
                request.decidedBy(),
                request.decidedAt(),
                request.guidance());
    }
}
