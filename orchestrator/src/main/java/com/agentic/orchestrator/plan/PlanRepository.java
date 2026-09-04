package com.agentic.orchestrator.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.agentic.orchestrator.persistence.StatePersistence;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Plan registry: an in-memory index that writes through to durable storage.
 *
 * <p>Plans are immutable, so the cache can never be stale — a plan is written once and read many
 * times. On startup the index is repopulated from storage before any run is recovered.
 */
@Repository
public class PlanRepository {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final StatePersistence persistence;

    public PlanRepository(StatePersistence persistence) {
        this.persistence = persistence;
    }

    public Plan save(Plan plan) {
        plans.put(plan.id(), plan);
        persistence.savePlan(plan);
        return plan;
    }

    /** Repopulates the index from storage without writing back. */
    public void restore(Plan plan) {
        plans.put(plan.id(), plan);
    }

    public Optional<Plan> findById(String id) {
        return Optional.ofNullable(plans.get(id));
    }

    public List<Plan> findAll() {
        List<Plan> all = new ArrayList<>(plans.values());
        all.sort(Comparator.comparing(Plan::createdAt).reversed());
        return all;
    }

    public void clear() {
        plans.clear();
    }
}
