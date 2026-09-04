package com.agentic.orchestrator.governance;

import java.util.List;

/**
 * The combined governance outcome for one node: what may happen, and why.
 *
 * <p>{@code reasons} is not logging. It is the impact summary a human reads before approving, and
 * the record of why an action was refused. An approval request with no stated reason is a rubber
 * stamp waiting to happen.
 *
 * @param matrixAction  what the autonomy matrix alone would have allowed
 * @param triggered     the policies that fired
 */
public record GovernanceVerdict(
        ControlAction action,
        ControlAction matrixAction,
        List<Policy> triggered,
        List<String> reasons) {

    public GovernanceVerdict {
        triggered = List.copyOf(triggered);
        reasons = List.copyOf(reasons);
    }

    public boolean requiresApproval() {
        return action == ControlAction.APPROVE;
    }

    public boolean denied() {
        return action == ControlAction.DENY;
    }

    /** The policy that caused a denial, for attribution in the audit trail. */
    public String denyingPolicy() {
        return triggered.stream()
                .filter(policy -> policy.effect() == Policy.Effect.DENY)
                .map(Policy::id)
                .findFirst()
                .orElse("AUTONOMY_MATRIX");
    }

    public String summary() {
        return String.join(" ", reasons);
    }
}
