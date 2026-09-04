package com.agentic.orchestrator.governance;

import com.agentic.orchestrator.persistence.StatePersistence;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Append-only decision record, per run.
 *
 * <p>Separate from the {@code EventLog} on purpose. The event log answers "what happened, in what
 * order" and is dense; this answers "what was decided, by whom, and why" and is sparse. Merging them
 * would bury a handful of consequential judgements in hundreds of state transitions.
 */
@Component
public class DecisionLog {

    private final Map<String, List<Decision>> byRun = new ConcurrentHashMap<>();
    private final StatePersistence persistence;

    public DecisionLog(StatePersistence persistence) {
        this.persistence = persistence;
    }

    public Decision record(Decision decision) {
        byRun.computeIfAbsent(decision.runId(), key -> new CopyOnWriteArrayList<>()).add(decision);
        persistence.saveDecision(decision);
        return decision;
    }

    /** Repopulates the log from storage without writing back. */
    public void restore(List<Decision> stored) {
        for (Decision decision : stored) {
            byRun.computeIfAbsent(decision.runId(), key -> new CopyOnWriteArrayList<>())
                    .add(decision);
        }
    }

    public List<Decision> forRun(String runId) {
        return List.copyOf(byRun.getOrDefault(runId, List.of()));
    }

    public List<Decision> forTask(String runId, String taskId) {
        return forRun(runId).stream()
                .filter(decision -> taskId.equals(decision.taskId()))
                .toList();
    }

    public void clear() {
        byRun.clear();
    }
}
