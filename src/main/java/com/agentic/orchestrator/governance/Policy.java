package com.agentic.orchestrator.governance;

import com.agentic.orchestrator.plan.Task;
import java.util.function.Predicate;

/**
 * A rule that constrains what an agent may do, independent of autonomy level.
 *
 * @param appliesTo when this policy is triggered by a task
 * @param effect    what happens when it triggers
 */
public record Policy(
        String id,
        String name,
        Category category,
        Effect effect,
        String rationale,
        Predicate<Task> appliesTo) {

    public enum Category {
        SECURITY,
        COMPLIANCE,
        CHANGE_CONTROL
    }

    public enum Effect {
        /** Refuse outright. Never overridable, at any autonomy level. */
        DENY,
        /** Force a human decision even where the autonomy matrix would have allowed AUTO. */
        REQUIRE_APPROVAL,
        /** Record a concern; do not impede. */
        WARN;

        public ControlAction toAction() {
            return switch (this) {
                case DENY -> ControlAction.DENY;
                case REQUIRE_APPROVAL -> ControlAction.APPROVE;
                case WARN -> ControlAction.AUTO;
            };
        }
    }

    public boolean triggeredBy(Task task) {
        return appliesTo.test(task);
    }
}
