package com.agentic.orchestrator.governance;

import com.agentic.orchestrator.plan.BlastRadius;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A node held pending a human decision.
 *
 * <p>Carries the impact summary and the triggering policies, so the approver is deciding on stated
 * facts rather than on a task id. Approving something you cannot see the consequences of is not
 * oversight.
 */
public final class ApprovalRequest {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    private final String id;
    private final String runId;
    private final String taskId;
    private final String taskTitle;
    private final BlastRadius blastRadius;
    private final List<String> reasons;
    private final List<String> policyIds;
    private final Instant requestedAt;

    private volatile Status status = Status.PENDING;
    private volatile String decidedBy;
    private volatile Instant decidedAt;
    private volatile String guidance;

    public ApprovalRequest(String runId, String taskId, String taskTitle, BlastRadius blastRadius,
                           List<String> reasons, List<String> policyIds) {
        this("apr-" + UUID.randomUUID().toString().substring(0, 8), runId, taskId, taskTitle,
                blastRadius, reasons, policyIds, Instant.now());
    }

    private ApprovalRequest(String id, String runId, String taskId, String taskTitle,
                            BlastRadius blastRadius, List<String> reasons, List<String> policyIds,
                            Instant requestedAt) {
        this.id = id;
        this.runId = runId;
        this.taskId = taskId;
        this.taskTitle = taskTitle;
        this.blastRadius = blastRadius;
        this.reasons = List.copyOf(reasons);
        this.policyIds = List.copyOf(policyIds);
        this.requestedAt = requestedAt;
    }

    /**
     * Rebuilds a request from storage, decision included. Used only by the persistence layer on
     * startup — this is the one path that may set a decided state without a human present, because
     * the human was present, before the restart.
     */
    public static ApprovalRequest restore(String id, String runId, String taskId, String taskTitle,
                                          BlastRadius blastRadius, List<String> reasons,
                                          List<String> policyIds, Instant requestedAt,
                                          Status status, String decidedBy, Instant decidedAt,
                                          String guidance) {
        ApprovalRequest request = new ApprovalRequest(id, runId, taskId, taskTitle, blastRadius,
                reasons, policyIds, requestedAt);
        request.status = status;
        request.decidedBy = decidedBy;
        request.decidedAt = decidedAt;
        request.guidance = guidance;
        return request;
    }

    public String id() {
        return id;
    }

    public String runId() {
        return runId;
    }

    public String taskId() {
        return taskId;
    }

    public String taskTitle() {
        return taskTitle;
    }

    public BlastRadius blastRadius() {
        return blastRadius;
    }

    public List<String> reasons() {
        return reasons;
    }

    public List<String> policyIds() {
        return policyIds;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Status status() {
        return status;
    }

    public String decidedBy() {
        return decidedBy;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    /**
     * Free-text direction supplied with a rejection. Today it is recorded for the audit trail; once
     * re-planning exists it becomes the input that reshapes the graph, which is why it is captured
     * now rather than discarded.
     */
    public String guidance() {
        return guidance;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    /**
     * Call only from the scheduler, holding the run's monitor. Deciding an approval and moving the
     * waiting node must be one atomic step, or a concurrent tick can observe an approved request
     * whose node is still parked.
     *
     * <p>Public rather than package-private because the scheduler owns that atomicity and lives in
     * another package. Putting the transition behind a governance-side service instead would need a
     * callback into the scheduler, which is a dependency cycle in exchange for no real safety.
     */
    public void approve(String decidedByWhom, String note) {
        status = Status.APPROVED;
        decidedBy = decidedByWhom;
        decidedAt = Instant.now();
        guidance = note;
    }

    /** @see #approve(String, String) for the calling contract. */
    public void reject(String decidedByWhom, String note) {
        status = Status.REJECTED;
        decidedBy = decidedByWhom;
        decidedAt = Instant.now();
        guidance = note;
    }
}
