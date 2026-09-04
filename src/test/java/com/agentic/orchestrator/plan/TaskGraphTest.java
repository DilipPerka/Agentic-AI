package com.agentic.orchestrator.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaskGraphTest {

    private static Task task(String id, Set<String> writes) {
        return new Task(id, id, id, Stage.IMPLEMENTATION, AgentRole.IMPLEMENTER, BlastRadius.LOW,
                List.of("done"), Set.of(), writes, false);
    }

    private static Task task(String id) {
        return task(id, Set.of());
    }

    @Test
    @DisplayName("rejects a cycle rather than levelling forever")
    void rejectsCycles() {
        List<Task> tasks = List.of(task("A"), task("B"), task("C"));
        List<Dependency> edges = List.of(
                Dependency.data("A", "B", "x"),
                Dependency.data("B", "C", "x"),
                Dependency.data("C", "A", "x"));

        assertThatThrownBy(() -> TaskGraph.of(tasks, edges))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    void rejectsDependencyOnUnknownTask() {
        assertThatThrownBy(() -> TaskGraph.of(
                List.of(task("A")),
                List.of(Dependency.data("A", "GHOST", "x"))))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("unknown task: GHOST");
    }

    @Test
    void rejectsSelfDependency() {
        assertThatThrownBy(() -> TaskGraph.of(
                List.of(task("A")),
                List.of(Dependency.data("A", "A", "x"))))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("Self-dependency");
    }

    @Test
    void rejectsDuplicateTaskIds() {
        assertThatThrownBy(() -> TaskGraph.of(List.of(task("A"), task("A")), List.of()))
                .isInstanceOf(GraphValidationException.class)
                .hasMessageContaining("Duplicate task id");
    }

    @Test
    void collapsesDuplicateEdges() {
        TaskGraph graph = TaskGraph.of(
                List.of(task("A"), task("B")),
                List.of(Dependency.data("A", "B", "first"), Dependency.data("A", "B", "again")));

        assertThat(graph.dependencies()).hasSize(1);
    }

    @Test
    @DisplayName("independent tasks land on the same execution level")
    void computesExecutionLevels() {
        List<Task> tasks = List.of(task("ROOT"), task("A"), task("B"), task("JOIN"));
        List<Dependency> edges = List.of(
                Dependency.data("ROOT", "A", "x"),
                Dependency.data("ROOT", "B", "x"),
                Dependency.data("A", "JOIN", "x"),
                Dependency.data("B", "JOIN", "x"));

        List<List<String>> levels = TaskGraph.of(tasks, edges).executionLevels();

        assertThat(levels).containsExactly(
                List.of("ROOT"),
                List.of("A", "B"),
                List.of("JOIN"));
    }

    @Test
    @DisplayName("serialises unordered tasks that write the same component")
    void serialisesWriteConflicts() {
        List<Task> tasks = List.of(
                task("WRITES_CTRL_1", Set.of("RedirectController", "AnalyticsService")),
                task("WRITES_CTRL_2", Set.of("RedirectController", "RateLimitFilter")));

        List<Dependency> added = TaskGraph.of(tasks, List.of()).writeConflictEdges();

        assertThat(added).hasSize(1);
        assertThat(added.get(0).from()).isEqualTo("WRITES_CTRL_1");
        assertThat(added.get(0).to()).isEqualTo("WRITES_CTRL_2");
        assertThat(added.get(0).kind()).isEqualTo(DependencyKind.CONTROL);
        assertThat(added.get(0).reason()).contains("RedirectController");
    }

    @Test
    @DisplayName("does not re-order tasks a real dependency already sequences")
    void ignoresConflictsBetweenAlreadyOrderedTasks() {
        List<Task> tasks = List.of(
                task("FIRST", Set.of("LinkService")),
                task("SECOND", Set.of("LinkService")));

        List<Dependency> added = TaskGraph.of(tasks,
                List.of(Dependency.data("FIRST", "SECOND", "real dependency"))).writeConflictEdges();

        assertThat(added).isEmpty();
    }

    @Test
    @DisplayName("ordering via a transitive path also counts as ordered")
    void ignoresConflictsResolvedTransitively() {
        List<Task> tasks = List.of(
                task("A", Set.of("Shared")),
                task("MIDDLE", Set.of()),
                task("C", Set.of("Shared")));

        List<Dependency> added = TaskGraph.of(tasks, List.of(
                Dependency.data("A", "MIDDLE", "x"),
                Dependency.data("MIDDLE", "C", "x"))).writeConflictEdges();

        assertThat(added).isEmpty();
    }

    @Test
    @DisplayName("conflict edges are themselves acyclic and produce a valid graph")
    void conflictEdgesKeepTheGraphValid() {
        List<Task> tasks = List.of(
                task("A", Set.of("Shared")),
                task("B", Set.of("Shared")),
                task("C", Set.of("Shared")));

        TaskGraph initial = TaskGraph.of(tasks, List.of());
        List<Dependency> added = initial.writeConflictEdges();

        // A->B is enough to order A and B; C then needs ordering against both, but B->C makes
        // A->C transitive, so only two edges should be required, not three.
        assertThat(added).hasSize(2);

        TaskGraph resolved = TaskGraph.of(tasks, added);
        assertThat(resolved.executionLevels()).containsExactly(
                List.of("A"), List.of("B"), List.of("C"));
    }
}
