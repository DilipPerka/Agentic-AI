package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.plan.Task;
import java.util.Map;

/**
 * Decides <em>what</em> a node should produce. The tools decide how it reaches disk.
 *
 * <p>The seam between orchestration and authorship. Two implementations are intended:
 * a deterministic blueprint library (reproducible, offline, no key) and an LLM-backed one (genuine
 * synthesis, non-deterministic). Everything else in the system — scheduling, gates, governance,
 * retry, rollback — is identical either way, which is the point of putting the boundary here.
 */
public interface AgentRuntime {

    AgentOutput produce(Task task, boolean degraded, Map<String, String> upstreamOutputs);

    /** Identifies which runtime produced a result, for the audit trail. */
    String name();
}
