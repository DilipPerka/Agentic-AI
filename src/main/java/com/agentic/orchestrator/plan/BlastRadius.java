package com.agentic.orchestrator.plan;

/**
 * How much damage a task could do if it goes wrong. Drives which tasks need human approval once
 * the governance layer lands; for now it is recorded during decomposition so the sequencing plan
 * already carries the risk signal.
 */
public enum BlastRadius {
    /** Tests, docs, comments, formatting. */
    LOW,
    /** Additive endpoints, config, contained refactors. */
    MEDIUM,
    /** Schema migrations, breaking API changes, dependency changes, hot-path edits. */
    HIGH,
    /** Secrets, build/CI config, deletions outside scope, history rewrites. Never auto-approved. */
    CRITICAL
}
