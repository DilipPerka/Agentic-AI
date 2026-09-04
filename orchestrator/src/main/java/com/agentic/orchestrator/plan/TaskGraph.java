package com.agentic.orchestrator.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An immutable DAG over {@link Task}s, with the graph algorithms decomposition needs: validation,
 * execution levelling, and write-conflict detection.
 *
 * <p>Immutable by design — adding an edge produces a new graph. Re-planning later will depend on
 * comparing plan versions, which is only tractable if a plan cannot mutate under you.
 */
public final class TaskGraph {

    private final List<Task> tasks;
    private final Map<String, Task> byId;
    private final Map<String, Integer> orderIndex;
    private final List<Dependency> dependencies;
    private final Map<String, List<String>> outgoing;

    private TaskGraph(List<Task> tasks, List<Dependency> dependencies) {
        this.tasks = List.copyOf(tasks);
        this.byId = new LinkedHashMap<>();
        this.orderIndex = new HashMap<>();
        for (int i = 0; i < this.tasks.size(); i++) {
            Task task = this.tasks.get(i);
            if (byId.put(task.id(), task) != null) {
                throw new GraphValidationException("Duplicate task id: " + task.id());
            }
            orderIndex.put(task.id(), i);
        }

        this.dependencies = dedupe(dependencies);
        this.outgoing = new LinkedHashMap<>();
        for (Task task : this.tasks) {
            outgoing.put(task.id(), new ArrayList<>());
        }
        for (Dependency dependency : this.dependencies) {
            requireKnown(dependency.from(), dependency);
            requireKnown(dependency.to(), dependency);
            if (dependency.from().equals(dependency.to())) {
                throw new GraphValidationException("Self-dependency on task " + dependency.from());
            }
            outgoing.get(dependency.from()).add(dependency.to());
        }

        detectCycles();
    }

    public static TaskGraph of(List<Task> tasks, List<Dependency> dependencies) {
        return new TaskGraph(tasks, dependencies);
    }

    public List<Task> tasks() {
        return tasks;
    }

    public List<Dependency> dependencies() {
        return dependencies;
    }

    /**
     * Groups tasks into execution levels: everything in level <i>n</i> may run in parallel, and
     * level <i>n+1</i> may not start until level <i>n</i> completes.
     *
     * <p>This is the "sequencing" half of the requirement. It is a schedule, not the only valid one —
     * a real scheduler dispatches each task as soon as <em>its own</em> dependencies are met rather
     * than waiting for a whole level. Levels are the honest way to <em>show</em> the parallelism.
     */
    public List<List<String>> executionLevels() {
        Map<String, Integer> indegree = indegrees();
        List<List<String>> levels = new ArrayList<>();

        List<String> frontier = tasks.stream()
                .map(Task::id)
                .filter(id -> indegree.get(id) == 0)
                .toList();

        int placed = 0;
        while (!frontier.isEmpty()) {
            levels.add(List.copyOf(frontier));
            placed += frontier.size();

            Set<String> next = new TreeSet<>((a, b) -> orderIndex.get(a) - orderIndex.get(b));
            for (String id : frontier) {
                for (String successor : outgoing.get(id)) {
                    if (indegree.merge(successor, -1, Integer::sum) == 0) {
                        next.add(successor);
                    }
                }
            }
            frontier = List.copyOf(next);
        }

        if (placed != tasks.size()) {
            throw new GraphValidationException("Graph contains a cycle; levelled " + placed
                    + " of " + tasks.size() + " tasks");
        }
        return levels;
    }

    /**
     * Finds pairs of tasks that could be dispatched concurrently and would write the same component,
     * and returns {@link DependencyKind#CONTROL} edges that serialise them.
     *
     * <p>The alternative — rejecting such a plan as invalid — would be wrong. Two features touching
     * one controller is normal; what is not acceptable is editing it from two agents at once. So the
     * planner resolves the collision by ordering rather than failing, and records why.
     *
     * <p>The condition is "not transitively ordered", not "on the same level". Level is one possible
     * schedule; unordered-ness is the property that actually permits concurrent dispatch.
     */
    public List<Dependency> writeConflictEdges() {
        List<Dependency> added = new ArrayList<>();
        List<Dependency> working = new ArrayList<>(dependencies);
        Map<String, Set<String>> reachable = transitiveClosure(working);

        for (int i = 0; i < tasks.size(); i++) {
            for (int j = i + 1; j < tasks.size(); j++) {
                Task earlier = tasks.get(i);
                Task later = tasks.get(j);

                Set<String> shared = new LinkedHashSet<>(earlier.writes());
                shared.retainAll(later.writes());
                if (shared.isEmpty()) {
                    continue;
                }
                if (reachable.get(earlier.id()).contains(later.id())
                        || reachable.get(later.id()).contains(earlier.id())) {
                    continue; // already ordered, no collision possible
                }

                Dependency edge = Dependency.control(earlier.id(), later.id(),
                        "Serialised: both write " + String.join(", ", shared));
                added.add(edge);
                working.add(edge);
                reachable = transitiveClosure(working);
            }
        }
        return reduce(added);
    }

    /**
     * Drops conflict edges that another path already implies. Pairwise detection over three mutually
     * conflicting tasks emits A-&gt;B, A-&gt;C and B-&gt;C, but A-&gt;B-&gt;C orders them just as
     * well. The redundant edge changes no schedule and only makes the graph harder to read, which
     * matters when a human has to review the sequencing.
     */
    private List<Dependency> reduce(List<Dependency> added) {
        List<Dependency> kept = new ArrayList<>(added);
        for (int candidate = kept.size() - 1; candidate >= 0; candidate--) {
            Dependency edge = kept.get(candidate);

            List<Dependency> without = new ArrayList<>(dependencies);
            for (int other = 0; other < kept.size(); other++) {
                if (other != candidate) {
                    without.add(kept.get(other));
                }
            }

            if (transitiveClosure(without).get(edge.from()).contains(edge.to())) {
                kept.remove(candidate);
            }
        }
        return kept;
    }

    private Map<String, Integer> indegrees() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (Task task : tasks) {
            indegree.put(task.id(), 0);
        }
        for (Dependency dependency : dependencies) {
            indegree.merge(dependency.to(), 1, Integer::sum);
        }
        return indegree;
    }

    private Map<String, Set<String>> transitiveClosure(List<Dependency> edges) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (Task task : tasks) {
            adjacency.put(task.id(), new ArrayList<>());
        }
        for (Dependency edge : edges) {
            adjacency.get(edge.from()).add(edge.to());
        }

        Map<String, Set<String>> closure = new LinkedHashMap<>();
        for (Task task : tasks) {
            Set<String> seen = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(adjacency.get(task.id()));
            while (!pending.isEmpty()) {
                String next = pending.pop();
                if (seen.add(next)) {
                    pending.addAll(adjacency.get(next));
                }
            }
            closure.put(task.id(), seen);
        }
        return closure;
    }

    private void detectCycles() {
        Map<String, Integer> indegree = indegrees();
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });

        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.poll();
            visited++;
            for (String successor : outgoing.get(id)) {
                if (indegree.merge(successor, -1, Integer::sum) == 0) {
                    ready.add(successor);
                }
            }
        }

        if (visited != tasks.size()) {
            List<String> stuck = indegree.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            throw new GraphValidationException("Cycle detected involving: " + stuck);
        }
    }

    private void requireKnown(String id, Dependency dependency) {
        if (!byId.containsKey(id)) {
            throw new GraphValidationException(
                    "Dependency " + dependency.from() + " -> " + dependency.to()
                            + " references unknown task: " + id);
        }
    }

    private static List<Dependency> dedupe(List<Dependency> dependencies) {
        Map<String, Dependency> unique = new LinkedHashMap<>();
        for (Dependency dependency : dependencies) {
            unique.putIfAbsent(dependency.from() + "->" + dependency.to(), dependency);
        }
        return List.copyOf(unique.values());
    }
}
