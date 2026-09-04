package com.agentic.orchestrator.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an agent produced.
 *
 * @param files    workspace-relative path to file content
 * @param evidence declared-write key to the artifact that satisfies it. Explicit rather than
 *                 inferred: guessing which file satisfies which declared output would let a node
 *                 pass its exit gate by producing something unrelated
 * @param summary  one line for the event log
 */
public record AgentOutput(Map<String, String> files, Map<String, String> evidence, String summary,
                          boolean supported) {

    public AgentOutput {
        files = Map.copyOf(files);
        evidence = Map.copyOf(evidence);
    }

    public static AgentOutput of(Map<String, String> files, Map<String, String> evidence,
                                 String summary) {
        return new AgentOutput(files, evidence, summary, true);
    }

    /**
     * The runtime has nothing for this task. Distinct from failure: it is a gap in coverage, not a
     * broken attempt, and the executor reports it as such rather than pretending to have tried.
     */
    public static AgentOutput unsupported(String reason) {
        return new AgentOutput(Map.of(), Map.of(), reason, false);
    }

    public Map<String, String> mutableFiles() {
        return new LinkedHashMap<>(files);
    }
}
