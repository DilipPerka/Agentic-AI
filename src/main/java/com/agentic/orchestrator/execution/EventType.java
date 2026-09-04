package com.agentic.orchestrator.execution;

/** Event vocabulary of the run log. Adding a state transition means adding an event for it. */
public enum EventType {
    RUN_CREATED,
    RUN_STARTED,
    NODE_STARTED,
    NODE_SUCCEEDED,
    NODE_FAILED,
    NODE_BLOCKED,
    NODE_CANCELLED,

    // Governance
    POLICY_DENIED,
    GATE_FAILED,
    NODE_AWAITING_APPROVAL,
    APPROVAL_REQUESTED,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,

    // Reliability
    RETRY_SCHEDULED,
    RETRIES_EXHAUSTED,
    NODE_TIMED_OUT,
    FALLBACK_ATTEMPTED,
    NODE_DEGRADED,
    NODE_COMPENSATING,
    NODE_ROLLED_BACK,
    COMPENSATION_FAILED,

    // Re-planning
    REPLAN_TRIGGERED,
    REPLAN_REJECTED,
    PLAN_APPROVAL_REQUESTED,
    PLAN_REVISED,

    // Run control
    STOP_REQUESTED,
    RUN_PAUSED,
    RUN_RESUMED,
    RUN_AWAITING_INPUT,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_STOPPED,
    RUN_ROLLED_BACK
}
