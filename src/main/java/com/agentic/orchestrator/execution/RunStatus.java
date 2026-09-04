package com.agentic.orchestrator.execution;

public enum RunStatus {

    CREATED(false),

    /** At least one node is running or dispatchable. */
    RUNNING(false),

    /**
     * A run reaches this state only when <em>nothing</em> can make progress. One branch waiting on a
     * human while another compiles keeps the run {@link #RUNNING} — that distinction is the
     * difference between an orchestrator and a script.
     */
    AWAITING_INPUT(false),

    /** Held by an operator. Running nodes finish; nothing new is dispatched. */
    PAUSED(false),

    /** Every node succeeded. */
    COMPLETED(true),

    /** Quiesced with at least one node failed or blocked. */
    FAILED(true),

    /** Halted by an explicit safe-stop. */
    STOPPED(true),

    /** Completed work was deliberately undone. */
    ROLLED_BACK(true);

    private final boolean terminal;

    RunStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
