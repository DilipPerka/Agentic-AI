package com.agentic.orchestrator.plan;

import java.util.List;
import java.util.Set;

/**
 * One actionable unit of work.
 *
 * <p>{@code reads} and {@code writes} are declared up front rather than discovered at runtime. That
 * single decision buys three things later: data dependencies can be derived instead of hand-wired,
 * write collisions between parallel tasks are detectable before execution, and an agent can be given
 * only the context it declared it needs.
 *
 * @param acceptanceCriteria what "done" means for this task, in observable terms
 * @param reads              artifact keys this task consumes
 * @param writes             component or artifact keys this task produces or modifies
 */
public record Task(
        String id,
        String title,
        String description,
        Stage stage,
        AgentRole agentRole,
        BlastRadius blastRadius,
        List<String> acceptanceCriteria,
        Set<String> reads,
        Set<String> writes,
        boolean requiresHumanApproval) {

    public Task {
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        reads = Set.copyOf(reads);
        writes = Set.copyOf(writes);
    }
}
