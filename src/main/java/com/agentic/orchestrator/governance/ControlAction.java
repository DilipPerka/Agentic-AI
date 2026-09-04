package com.agentic.orchestrator.governance;

/** What governance permits for a node. */
public enum ControlAction {

    /** Proceed without asking. */
    AUTO,

    /** Hold in a durable waiting state until a human decides. */
    APPROVE,

    /** Refuse. Not overridable by autonomy level. */
    DENY;

    /** The stricter of two actions. Governance combines signals by taking the most restrictive. */
    public ControlAction strictest(ControlAction other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
