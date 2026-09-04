package com.agentic.orchestrator.api;

import com.agentic.orchestrator.plan.BlastRadius;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.Task;
import java.util.List;

/**
 * A plan plus the derived numbers a reader actually wants first: how much of it can run in parallel,
 * how deep the critical path is, and where humans are required.
 */
public record PlanResponse(Plan plan, Summary summary) {

    public static PlanResponse of(Plan plan) {
        return new PlanResponse(plan, Summary.from(plan));
    }

    public record Summary(
            String scenarioType,
            int taskCount,
            int dependencyCount,
            int levelCount,
            int maxParallelism,
            List<String> humanApprovalTasks,
            List<String> highRiskTasks,
            int ambiguityCount) {

        static Summary from(Plan plan) {
            return new Summary(
                    plan.requirement().scenarioType().name(),
                    plan.tasks().size(),
                    plan.dependencies().size(),
                    plan.depth(),
                    plan.maxParallelism(),
                    plan.tasks().stream().filter(Task::requiresHumanApproval).map(Task::id).toList(),
                    plan.tasks().stream()
                            .filter(task -> task.blastRadius() == BlastRadius.HIGH
                                    || task.blastRadius() == BlastRadius.CRITICAL)
                            .map(Task::id)
                            .toList(),
                    plan.requirement().ambiguities().size());
        }
    }
}
