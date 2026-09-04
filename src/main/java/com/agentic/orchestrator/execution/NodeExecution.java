package com.agentic.orchestrator.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable execution state for one task within one run.
 *
 * <p>Mutation is confined to the owning {@link Run}'s monitor — every transition here is called from
 * inside a {@code synchronized (run.lock())} block in {@link RunScheduler}. Fields are volatile so
 * that readers outside that lock (the API, a snapshot) see a consistent recent value without having
 * to contend with the scheduler.
 */
public final class NodeExecution {

    private final String taskId;
    private volatile NodeState state = NodeState.PENDING;
    private volatile int attempt;
    private volatile Instant startedAt;
    private volatile Instant endedAt;
    private volatile String message;
    private volatile boolean fallbackAttempted;
    private volatile boolean completedDegraded;
    private volatile String fingerprint;
    private final Map<String, String> outputs = new ConcurrentHashMap<>();

    public NodeExecution(String taskId) {
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }

    public NodeState state() {
        return state;
    }

    public int attempt() {
        return attempt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public String message() {
        return message;
    }

    public Map<String, String> outputs() {
        return Map.copyOf(outputs);
    }

    public Long durationMs() {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return Duration.between(startedAt, endedAt).toMillis();
    }

    void markRunning() {
        state = NodeState.RUNNING;
        attempt++;
        startedAt = Instant.now();
    }

    void markAwaitingApproval(String reason) {
        state = NodeState.AWAITING_APPROVAL;
        message = reason;
    }

    /** Returns a node to the ready pool — used when an approval is granted. */
    void markPending() {
        state = NodeState.PENDING;
        message = null;
    }

    public boolean fallbackAttempted() {
        return fallbackAttempted;
    }

    /** Succeeded only via the degraded fallback path, so the output is weaker than intended. */
    public boolean completedDegraded() {
        return completedDegraded;
    }

    /**
     * The content fingerprint of this node's definition and inputs at the moment it succeeded.
     * Re-planning compares it against a freshly computed one to decide whether the work is stale.
     */
    public String fingerprint() {
        return fingerprint;
    }

    void markSucceeded(NodeResult result, boolean degraded, String succeededFingerprint) {
        state = NodeState.SUCCEEDED;
        endedAt = Instant.now();
        message = result.summary();
        completedDegraded = degraded;
        fingerprint = succeededFingerprint;
        outputs.putAll(result.outputs());
    }

    void markRetrying(String reason) {
        state = NodeState.RETRYING;
        message = reason;
    }

    /** Returns the node to the ready pool for its one degraded attempt. */
    void markAwaitingFallback(String reason) {
        state = NodeState.PENDING;
        fallbackAttempted = true;
        message = reason;
    }

    void markCompensating() {
        state = NodeState.COMPENSATING;
        message = "Undoing side effects";
    }

    void markRolledBack(String summary) {
        state = NodeState.ROLLED_BACK;
        endedAt = Instant.now();
        message = summary;
    }

    void markFailed(String reason) {
        state = NodeState.FAILED;
        endedAt = Instant.now();
        message = reason;
    }

    void markBlocked(String reason) {
        state = NodeState.BLOCKED;
        endedAt = Instant.now();
        message = reason;
    }

    void markCancelled(String reason) {
        state = NodeState.CANCELLED;
        endedAt = Instant.now();
        message = reason;
    }

    /**
     * Rebuilds node state from storage. Persistence-layer use only: it is the one path that sets a
     * state without a corresponding transition, because the transition already happened and was
     * recorded before the restart.
     */
    public void restore(NodeState restoredState, int restoredAttempt, Instant restoredStartedAt,
                        Instant restoredEndedAt, String restoredMessage,
                        Map<String, String> restoredOutputs, boolean restoredFallbackAttempted,
                        boolean restoredCompletedDegraded, String restoredFingerprint) {
        this.fingerprint = restoredFingerprint;
        this.state = restoredState;
        this.attempt = restoredAttempt;
        this.startedAt = restoredStartedAt;
        this.endedAt = restoredEndedAt;
        this.message = restoredMessage;
        this.fallbackAttempted = restoredFallbackAttempted;
        this.completedDegraded = restoredCompletedDegraded;
        this.outputs.putAll(restoredOutputs);
    }

    /**
     * Fails a node that was in flight when the process died.
     *
     * <p>Not re-queued, deliberately. We cannot distinguish "the node finished and we crashed before
     * recording it" from "the node never ran", and with no compensation mechanism yet, re-running
     * work that may have had side effects is the more dangerous guess. Failing it puts the decision
     * in front of a human. This becomes retry-after-compensation once rollback exists.
     */
    void markInterruptedByRestart() {
        // RETRYING nodes come back here too: their backoff timer lived in the dead process, so
        // without this they would sit waiting for a wake-up that will never arrive.
        state = NodeState.FAILED;
        endedAt = Instant.now();
        message = "Interrupted by restart while RUNNING; cannot determine whether it completed "
                + "or what side effects it left. Requires human review.";
    }
}
