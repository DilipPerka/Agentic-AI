package com.agentic.orchestrator.execution;

/**
 * Performs the work of one node.
 *
 * <p>The seam between the orchestration engine and whatever actually does the work. The engine knows
 * nothing about agents, LLMs, Maven or git — swapping the executor swaps all of that without
 * touching scheduling, state or the event log.
 */
public interface NodeExecutor {

    NodeResult execute(ExecutionContext context);
}
