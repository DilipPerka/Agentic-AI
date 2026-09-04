package com.agentic.orchestrator.plan;

import com.agentic.orchestrator.requirement.Requirement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A decomposed requirement: the tasks, how they depend on each other, and a concrete schedule.
 *
 * <p>Immutable and versioned. Re-planning never mutates a plan; it produces version n+1 that names
 * what it supersedes. That is what makes "what changed between plans" a question with an answer.
 *
 * @param executionLevels tasks grouped into parallel batches; index 0 runs first
 * @param planningNotes   decisions the planner made and why — the audit trail for the decomposition
 *                        itself, so the sequencing can be questioned rather than just accepted
 * @param supersedes      id of the plan this replaced, or null for a first plan
 */
public record Plan(
        String id,
        Requirement requirement,
        List<Task> tasks,
        List<Dependency> dependencies,
        List<List<String>> executionLevels,
        List<String> planningNotes,
        Instant createdAt,
        int version,
        String supersedes) {

    public Plan {
        tasks = List.copyOf(tasks);
        dependencies = List.copyOf(dependencies);
        executionLevels = executionLevels.stream().map(List::copyOf).toList();
        planningNotes = List.copyOf(planningNotes);
    }

    /** A first plan: version 1, superseding nothing. */
    public Plan(String id, Requirement requirement, List<Task> tasks, List<Dependency> dependencies,
                List<List<String>> executionLevels, List<String> planningNotes, Instant createdAt) {
        this(id, requirement, tasks, dependencies, executionLevels, planningNotes, createdAt,
                1, null);
    }

    /** Widest parallel batch — how much concurrency this plan actually offers. */
    public int maxParallelism() {
        return executionLevels.stream().mapToInt(List::size).max().orElse(0);
    }

    /** Number of sequential batches — the critical-path length in levels. */
    public int depth() {
        return executionLevels.size();
    }

    public Task task(String taskId) {
        return tasks.stream().filter(task -> task.id().equals(taskId)).findFirst().orElse(null);
    }

    /**
     * Hash of the plan's <em>shape</em> — its task ids and edges — deliberately excluding the id,
     * timestamp and notes.
     *
     * <p>Used to detect re-plan oscillation: a planner that keeps flipping between two graphs would
     * loop forever, and identity comparison would never catch it because each plan gets a fresh id
     * and timestamp. Two plans that would execute identically must hash identically.
     */
    public String structuralHash() {
        String shape = tasks.stream().map(Task::id).sorted().collect(Collectors.joining(","))
                + "|"
                + dependencies.stream()
                        .map(edge -> edge.from() + ">" + edge.to())
                        .sorted()
                        .collect(Collectors.joining(","));
        return sha256(shape);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JLS", impossible);
        }
    }
}
