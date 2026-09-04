package com.agentic.orchestrator.governance;

import com.agentic.orchestrator.plan.Task;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Combines the autonomy matrix and the policy set into a single verdict per node.
 *
 * <p>Combination rule: <b>take the strictest signal</b>. A policy may only ever tighten what the
 * matrix allows, never loosen it. So raising autonomy can remove an approval the matrix imposed, but
 * it can never remove one a policy imposed, and it can never clear a DENY.
 */
@Service
public class GovernanceService {

    private final ApprovalMatrix matrix;
    private final PolicyEngine policies;

    public GovernanceService(ApprovalMatrix matrix, PolicyEngine policies) {
        this.matrix = matrix;
        this.policies = policies;
    }

    public GovernanceVerdict evaluate(Task task, AutonomyLevel autonomy) {
        ControlAction matrixAction = matrix.actionFor(autonomy, task.blastRadius());
        List<Policy> triggered = policies.evaluate(task);
        List<String> reasons = new ArrayList<>();

        if (matrixAction != ControlAction.AUTO) {
            reasons.add("Autonomy " + autonomy + " requires " + matrixAction
                    + " for " + task.blastRadius() + " blast radius.");
        }

        ControlAction action = matrixAction;
        for (Policy policy : triggered) {
            action = action.strictest(policy.effect().toAction());
            reasons.add("[" + policy.id() + "] " + policy.name() + " (" + policy.effect() + "). "
                    + policy.rationale());
        }

        if (reasons.isEmpty()) {
            reasons.add("No policy triggered and autonomy " + autonomy + " permits "
                    + task.blastRadius() + " work unattended.");
        }

        return new GovernanceVerdict(action, matrixAction, triggered, reasons);
    }
}
