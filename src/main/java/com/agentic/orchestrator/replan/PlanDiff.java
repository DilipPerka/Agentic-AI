package com.agentic.orchestrator.replan;

import com.agentic.orchestrator.plan.BlastRadius;
import java.util.List;

/**
 * What changed between two plan versions.
 *
 * <p>The diff is the thing a human approves. "The plan changed" is not reviewable; "it removes three
 * tasks you already approved and adds a schema migration" is.
 *
 * @param removedWithCompletedWork  removed tasks that had already succeeded — the expensive case,
 *                                  because their output has to be undone
 * @param highestAddedBlastRadius   the riskiest thing the new plan introduces, or null if nothing
 *                                  was added
 */
public record PlanDiff(
        List<String> added,
        List<String> removed,
        List<String> retained,
        List<String> removedWithCompletedWork,
        BlastRadius highestAddedBlastRadius,
        boolean structurallyIdentical) {

    public PlanDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        retained = List.copyOf(retained);
        removedWithCompletedWork = List.copyOf(removedWithCompletedWork);
    }

    public int churn() {
        return added.size() + removed.size();
    }

    /**
     * Whether admitting this plan is itself a high-impact action.
     *
     * <p>Three independent reasons, any of which is enough:
     * <ul>
     *   <li>it discards work that already completed — someone should know before it is undone;
     *   <li>it introduces high or critical blast radius that the previous plan did not have;
     *   <li>it changes more of the graph than the configured threshold, which is the signal that the
     *       planner has reinterpreted the requirement rather than adjusted it.
     * </ul>
     */
    public boolean requiresApproval(int churnThreshold) {
        return !removedWithCompletedWork.isEmpty()
                || highestAddedBlastRadius == BlastRadius.HIGH
                || highestAddedBlastRadius == BlastRadius.CRITICAL
                || churn() > churnThreshold;
    }

    public List<String> reasons(int churnThreshold) {
        List<String> reasons = new java.util.ArrayList<>();
        if (!removedWithCompletedWork.isEmpty()) {
            reasons.add("Discards completed work: "
                    + String.join(", ", removedWithCompletedWork));
        }
        if (highestAddedBlastRadius == BlastRadius.HIGH
                || highestAddedBlastRadius == BlastRadius.CRITICAL) {
            reasons.add("Introduces " + highestAddedBlastRadius + " blast radius work");
        }
        if (churn() > churnThreshold) {
            reasons.add("Changes " + churn() + " tasks, above the threshold of " + churnThreshold);
        }
        reasons.add("Added: " + (added.isEmpty() ? "none" : String.join(", ", added)));
        reasons.add("Removed: " + (removed.isEmpty() ? "none" : String.join(", ", removed)));
        reasons.add("Retained: " + retained.size() + " task(s)");
        return List.copyOf(reasons);
    }

    public String summary() {
        return "+" + added.size() + " -" + removed.size() + " =" + retained.size();
    }
}
