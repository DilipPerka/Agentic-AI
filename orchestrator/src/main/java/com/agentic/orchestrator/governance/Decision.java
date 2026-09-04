package com.agentic.orchestrator.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A recorded choice and its reasoning (CR4.5).
 *
 * <p>First-class rather than a log line. The lineage question a reviewer asks is "why does this
 * exist, and who decided that" — answerable only if decisions are stored objects with an actor, a
 * rationale and the alternatives that were passed over.
 *
 * @param actor       {@code AGENT:ROLE}, {@code POLICY:ID}, {@code HUMAN:id} or {@code SCHEDULER}
 * @param alternatives options that were available and not taken
 */
public record Decision(
        String id,
        String runId,
        String taskId,
        String actor,
        String question,
        String choice,
        String rationale,
        List<String> alternatives,
        Instant timestamp) {

    public Decision {
        alternatives = List.copyOf(alternatives);
    }

    public static Decision of(String runId, String taskId, String actor, String question,
                              String choice, String rationale, List<String> alternatives) {
        return new Decision("dec-" + UUID.randomUUID().toString().substring(0, 8),
                runId, taskId, actor, question, choice, rationale, alternatives, Instant.now());
    }
}
