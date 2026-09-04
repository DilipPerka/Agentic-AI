package com.agentic.orchestrator.governance;

/**
 * How much the operator lets agents do without asking (CR7).
 *
 * <p>Autonomy tunes <em>approval</em>. It never overrides <em>policy</em>: a policy DENY halts an L3
 * run exactly as it halts an L0 run. Keeping the two orthogonal is what makes "controlled autonomy"
 * a control rather than a slogan — otherwise the highest autonomy level quietly becomes a way to
 * switch the guardrails off.
 */
public enum AutonomyLevel {

    /** Agents propose; every action waits for a human. Nothing reaches the workspace unreviewed. */
    L0_OBSERVE,

    /** Agents may do low-impact work unattended; anything else waits. */
    L1_SUPERVISED,

    /** Default. Agents work freely; high-impact actions wait for a human. */
    L2_DELEGATED,

    /** Agents proceed unattended except for critical actions, which are never automatic. */
    L3_AUTONOMOUS
}
