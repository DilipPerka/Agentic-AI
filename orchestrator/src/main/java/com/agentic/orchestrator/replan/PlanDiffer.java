package com.agentic.orchestrator.replan;

import com.agentic.orchestrator.plan.BlastRadius;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.Task;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class PlanDiffer {

    /**
     * @param alreadyCompleted predicate answering whether a task id had succeeded under the current
     *                         plan; used to spot removals that discard finished work
     */
    public PlanDiff diff(Plan current, Plan next, Predicate<String> alreadyCompleted) {
        Set<String> currentIds = ids(current);
        Set<String> nextIds = ids(next);

        List<String> added = nextIds.stream().filter(id -> !currentIds.contains(id)).sorted().toList();
        List<String> removed = currentIds.stream().filter(id -> !nextIds.contains(id)).sorted().toList();
        List<String> retained = currentIds.stream().filter(nextIds::contains).sorted().toList();

        List<String> removedWithWork = new ArrayList<>(
                removed.stream().filter(alreadyCompleted).toList());

        BlastRadius highestAdded = next.tasks().stream()
                .filter(task -> added.contains(task.id()))
                .map(Task::blastRadius)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(null);

        return new PlanDiff(added, removed, retained, removedWithWork, highestAdded,
                current.structuralHash().equals(next.structuralHash()));
    }

    private static Set<String> ids(Plan plan) {
        return plan.tasks().stream().map(Task::id)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }
}
