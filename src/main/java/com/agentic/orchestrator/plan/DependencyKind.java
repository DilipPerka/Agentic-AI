package com.agentic.orchestrator.plan;

public enum DependencyKind {
    /** Downstream consumes an artifact the upstream produces. */
    DATA,
    /** Pure ordering, no data flow. Used to serialise tasks that would otherwise collide. */
    CONTROL
}
