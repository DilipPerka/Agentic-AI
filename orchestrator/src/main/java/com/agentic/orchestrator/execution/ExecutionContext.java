package com.agentic.orchestrator.execution;

import com.agentic.orchestrator.plan.Task;
import java.util.Map;

/**
 * Everything a node executor is given.
 *
 * @param upstreamOutputs outputs of this node's declared predecessors only, never the whole run
 *                        history — least-privilege context, and the basis for the node's fingerprint
 * @param degraded        this is the fallback attempt after retries were exhausted. An executor that
 *                        can do something simpler and safer should do it now; one that cannot should
 *                        fail, and must not pretend otherwise
 * @param nodeOutputs     the node's <em>own</em> recorded outputs. Empty during forward execution
 *                        and populated during compensation, where undoing work requires knowing
 *                        what that work produced — a commit sha, for instance
 */
public record ExecutionContext(
        String runId,
        Task task,
        int attempt,
        boolean degraded,
        Map<String, String> upstreamOutputs,
        Map<String, String> nodeOutputs,
        String baseRunId) {

    public ExecutionContext {
        upstreamOutputs = Map.copyOf(upstreamOutputs);
        nodeOutputs = Map.copyOf(nodeOutputs);
    }

    public ExecutionContext(String runId, Task task, int attempt, boolean degraded,
                            Map<String, String> upstreamOutputs, Map<String, String> nodeOutputs) {
        this(runId, task, attempt, degraded, upstreamOutputs, nodeOutputs, null);
    }

    public ExecutionContext(String runId, Task task, int attempt, boolean degraded,
                            Map<String, String> upstreamOutputs) {
        this(runId, task, attempt, degraded, upstreamOutputs, Map.of(), null);
    }

    public ExecutionContext(String runId, Task task, int attempt,
                            Map<String, String> upstreamOutputs) {
        this(runId, task, attempt, false, upstreamOutputs, Map.of(), null);
    }
}
