package com.agentic.orchestrator.plan;

/**
 * The SDLC stages the orchestrator coordinates (CR4.1).
 *
 * <p>{@link #CLARIFICATION} and {@link #IMPACT_ANALYSIS} are conditional: they appear only for
 * ambiguous and brownfield requirements respectively. That is what makes the produced graph
 * shape-dependent on the requirement rather than a fixed template.
 */
public enum Stage {
    REQUIREMENTS,
    CLARIFICATION,
    IMPACT_ANALYSIS,
    DESIGN,
    IMPLEMENTATION,
    TESTING,
    REVIEW,
    DOCUMENTATION,
    RELEASE_READINESS
}
