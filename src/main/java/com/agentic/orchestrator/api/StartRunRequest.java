package com.agentic.orchestrator.api;

import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.requirement.ScenarioType;

/**
 * Start a run either from an existing plan or straight from a requirement.
 *
 * <p>The requirement path is a convenience that decomposes first and then runs the result. It is one
 * call for the common case; {@code planId} remains available when a plan should be inspected, or
 * approved, before anything executes.
 *
 * @param planId       an existing plan to execute; takes precedence if both are supplied
 * @param requirement  free text to decompose and then run
 * @param scenarioHint optional scenario override applied when decomposing {@code requirement}
 * @param autonomyLevel how much the agents may do unattended; defaults to
 *                      {@link AutonomyLevel#L2_DELEGATED}
 * @param baseRunId    an earlier run whose workspace this one continues from. Brownfield work
 *                     needs an existing codebase to reason about and modify; without it, "add
 *                     analytics to the existing service" would write analytics into an empty
 *                     directory and fail to compile for a reason that has nothing to do with the
 *                     change being asked for
 */
public record StartRunRequest(String planId, String requirement, ScenarioType scenarioHint,
                              AutonomyLevel autonomyLevel, String baseRunId) {

    /** A greenfield request: no baseline to continue from. */
    public StartRunRequest(String planId, String requirement, ScenarioType scenarioHint,
                           AutonomyLevel autonomyLevel) {
        this(planId, requirement, scenarioHint, autonomyLevel, null);
    }

    public boolean hasPlanId() {
        return planId != null && !planId.isBlank();
    }

    public boolean hasRequirement() {
        return requirement != null && !requirement.isBlank();
    }

    /**
     * Defaults to L2 rather than L3. An unspecified autonomy level is an operator who has not
     * thought about it, and the safe reading of that is "still ask me about high-impact work".
     */
    public AutonomyLevel autonomyOrDefault() {
        return autonomyLevel == null ? AutonomyLevel.L2_DELEGATED : autonomyLevel;
    }
}
