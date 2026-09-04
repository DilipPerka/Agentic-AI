package com.agentic.orchestrator.execution;

/**
 * Lifecycle of a single node within a run.
 *
 * <p>The full set from the orchestration design is declared here even though this increment only
 * reaches a subset. Declaring the target state machine once avoids a rename-everything change when
 * approvals, retries and rollback land, and it documents where the engine is heading.
 *
 * <p><b>Soft vs hard terminal:</b> {@link #SUCCEEDED}, {@link #FAILED} and {@link #BLOCKED} are
 * terminal for scheduling but re-openable by invalidation when re-planning arrives. Only
 * {@link #CANCELLED} is permanently terminal.
 */
public enum NodeState {

    /** Exists; dependencies not yet satisfied. */
    PENDING(false),

    /** Reachable in a later increment: durable pause awaiting a human decision. */
    AWAITING_APPROVAL(false),

    /** Executing now. */
    RUNNING(false),

    /** Reachable in a later increment: failed, backoff timer armed. */
    RETRYING(false),

    /** Exit criteria met. */
    SUCCEEDED(true),

    /** Executed and failed. */
    FAILED(true),

    /** Cannot ever run: an upstream dependency failed, was blocked, or was cancelled. */
    BLOCKED(true),

    /** Reachable in a later increment: rollback in progress. */
    COMPENSATING(false),

    /** Reachable in a later increment: side effects undone. */
    ROLLED_BACK(true),

    /** Reachable in a later increment: upstream output changed, must re-run. */
    INVALIDATED(false),

    /** Stopped before it started. */
    CANCELLED(true);

    private final boolean terminal;

    NodeState(boolean terminal) {
        this.terminal = terminal;
    }

    /** No further scheduling will happen from this state without an external trigger. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Terminal, but not successfully — dependents can never run. */
    public boolean isUnsuccessfulTerminal() {
        return terminal && this != SUCCEEDED;
    }
}
