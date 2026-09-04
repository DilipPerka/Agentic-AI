package com.agentic.orchestrator.replan;

/** Why a re-plan was attempted. Recorded on the resulting plan and in the decision log. */
public enum ReplanTrigger {

    /** A human answered a clarification or amended the requirement. */
    REQUIREMENT_AMENDED,

    /**
     * An approval was refused with direction attached. This is the one that turns a human from a
     * gate into part of the loop: the refusal reshapes the graph instead of only failing a node.
     */
    APPROVAL_REJECTED_WITH_GUIDANCE,

    /** An operator asked for one explicitly. */
    MANUAL
}
