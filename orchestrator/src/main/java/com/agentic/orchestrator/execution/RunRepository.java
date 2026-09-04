package com.agentic.orchestrator.execution;

import com.agentic.orchestrator.persistence.StatePersistence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Run registry: live objects in memory, written through to durable storage on every transition.
 *
 * <p>The in-memory object stays the source of truth <em>during</em> a run — it owns the monitor the
 * scheduler synchronises on, and routing every state read through the database would put I/O in the
 * middle of the state machine. Storage is a durable mirror, updated inside the same critical section
 * that made the change, so what is on disk is always a state the run actually passed through.
 */
@Repository
public class RunRepository {

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final StatePersistence persistence;

    public RunRepository(StatePersistence persistence) {
        this.persistence = persistence;
    }

    public Run save(Run run) {
        runs.put(run.id(), run);
        persistence.saveRun(run);
        return run;
    }

    /** Mirrors the current state of an already-registered run. Called after every transition. */
    public void persist(Run run) {
        persistence.saveRun(run);
    }

    /**
     * As {@link #persist}, plus removal of stored nodes the run no longer has. Only used after a
     * re-plan, where the task set itself changed.
     */
    public void persistAfterReplan(Run run) {
        persistence.saveRun(run);
        persistence.pruneRunNodes(run);
    }

    /** Repopulates the registry from storage without writing back. */
    public void restore(Run run) {
        runs.put(run.id(), run);
    }

    public Optional<Run> findById(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    public List<Run> findAll() {
        List<Run> all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing(Run::createdAt).reversed());
        return all;
    }

    public void clear() {
        runs.clear();
    }
}
