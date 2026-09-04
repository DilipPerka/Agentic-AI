package com.agentic.orchestrator.replan;

/**
 * Result of a re-plan attempt.
 *
 * <p>Refusals are outcomes, not errors: hitting the re-plan bound or detecting oscillation is the
 * system working, and the caller needs to be told which one happened.
 */
public record ReplanOutcome(Status status, String detail, PlanDiff diff, String newPlanId) {

    public enum Status {
        /** New plan admitted; the run is executing it. */
        ADMITTED,
        /** The plan diff was high-impact; a human must approve the plan itself before it applies. */
        AWAITING_PLAN_APPROVAL,
        /** The new plan would execute identically to the current one. */
        NO_CHANGE,
        /** The planner produced a shape it has already produced in this run. */
        OSCILLATION_DETECTED,
        /** The run has re-planned as often as it is permitted to. */
        BOUND_REACHED,
        /** The run is not in a state where re-planning is meaningful. */
        REJECTED
    }

    public static ReplanOutcome admitted(PlanDiff diff, String newPlanId) {
        return new ReplanOutcome(Status.ADMITTED,
                "New plan admitted (" + diff.summary() + ")", diff, newPlanId);
    }

    public static ReplanOutcome awaitingApproval(PlanDiff diff, String approvalId) {
        return new ReplanOutcome(Status.AWAITING_PLAN_APPROVAL,
                "Plan change is high-impact; approval " + approvalId + " raised", diff, null);
    }

    public static ReplanOutcome of(Status status, String detail) {
        return new ReplanOutcome(status, detail, null, null);
    }

    public boolean applied() {
        return status == Status.ADMITTED;
    }
}
