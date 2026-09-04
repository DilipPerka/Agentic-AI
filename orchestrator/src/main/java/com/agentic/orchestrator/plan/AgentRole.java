package com.agentic.orchestrator.plan;

/**
 * Who owns a task. {@link #HUMAN} is a first-class role, not an absence of one: tasks assigned to
 * HUMAN are the points where the orchestrator hands control back for oversight (CR7).
 */
public enum AgentRole {
    REQUIREMENT_ANALYST,
    CODEBASE_ANALYST,
    ARCHITECT,
    IMPLEMENTER,
    TEST_ENGINEER,
    REVIEWER,
    DOC_WRITER,
    RELEASE_MANAGER,
    HUMAN
}
