package com.agentic.orchestrator.execution;

import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.plan.Dependency;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.Task;
import com.agentic.orchestrator.replan.ReplanTrigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * One execution of a {@link Plan}.
 *
 * <p>The plan is immutable but not permanent: {@link #adopt} swaps in a new version while carrying
 * forward the nodes that are still valid, so re-planning does not throw away completed work. Every
 * mutator here runs under the run's monitor, held by the scheduler.
 */
public final class Run {

    private final String id;
    private final AutonomyLevel autonomyLevel;
    /**
     * The run whose workspace this one starts from, or {@code null} for a greenfield run.
     *
     * <p>This is what makes "add analytics to the existing service" concrete: a brownfield run
     * begins with the baseline's files on disk, so its impact analysis has something real to read
     * and its implementation nodes modify code rather than inventing it in an empty directory.
     */
    private final String baseRunId;
    private final Object lock = new Object();
    private final Set<String> seenPlanShapes = new CopyOnWriteArraySet<>();

    private volatile Plan plan;
    private volatile Map<String, Task> tasksById;
    private volatile Map<String, List<String>> predecessors;
    private volatile Map<String, NodeExecution> nodes;

    private volatile Instant createdAt = Instant.now();
    private volatile RunStatus status = RunStatus.CREATED;
    private volatile Instant startedAt;
    private volatile Instant endedAt;
    private volatile boolean stopRequested;
    private volatile String stopReason;
    private volatile boolean paused;
    private volatile int replanCount;
    private volatile String pendingReplanGuidance;
    private volatile ReplanTrigger pendingReplanTrigger;

    public Run(Plan plan, AutonomyLevel autonomyLevel) {
        this(plan, autonomyLevel, null);
    }

    public Run(Plan plan, AutonomyLevel autonomyLevel, String baseRunId) {
        this("run-" + UUID.randomUUID().toString().substring(0, 8), plan, autonomyLevel, baseRunId);
    }

    public Run(String id, Plan plan, AutonomyLevel autonomyLevel) {
        this(id, plan, autonomyLevel, null);
    }

    public Run(String id, Plan plan, AutonomyLevel autonomyLevel, String baseRunId) {
        this.id = id;
        this.autonomyLevel = autonomyLevel;
        this.baseRunId = baseRunId;
        index(plan, Map.of(), Set.of());
        this.seenPlanShapes.add(plan.structuralHash());
    }

    /** The run this one's workspace was seeded from, or {@code null} for greenfield. */
    public String baseRunId() {
        return baseRunId;
    }

    /** (Re)builds the task, node and predecessor indexes for a plan. */
    private void index(Plan newPlan, Map<String, NodeExecution> carried, Set<String> invalidated) {
        Map<String, Task> newTasks = new LinkedHashMap<>();
        Map<String, NodeExecution> newNodes = new LinkedHashMap<>();
        Map<String, List<String>> newPredecessors = new LinkedHashMap<>();

        for (Task task : newPlan.tasks()) {
            newTasks.put(task.id(), task);
            newPredecessors.put(task.id(), new ArrayList<>());

            NodeExecution existing = carried.get(task.id());

            // Carry a node forward only if it actually completed and its inputs did not change.
            // Everything else starts fresh — including nodes that FAILED or were BLOCKED. Leaving a
            // failure in place would make re-planning cosmetic: the graph would change shape and
            // then immediately block on the same dead node it was rebuilt to get past.
            boolean reusable = existing != null
                    && existing.state() == NodeState.SUCCEEDED
                    && !invalidated.contains(task.id());

            newNodes.put(task.id(), reusable ? existing : new NodeExecution(task.id()));
        }
        for (Dependency dependency : newPlan.dependencies()) {
            newPredecessors.get(dependency.to()).add(dependency.from());
        }

        this.plan = newPlan;
        this.tasksById = newTasks;
        this.nodes = newNodes;
        this.predecessors = newPredecessors;
    }

    /**
     * Replaces the active plan.
     *
     * @param invalidated tasks whose completed work is stale and must be redone even though they
     *                    still exist in the new plan
     */
    void adopt(Plan newPlan, Set<String> invalidated) {
        index(newPlan, nodes, invalidated);
        replanCount++;
        seenPlanShapes.add(newPlan.structuralHash());
        pendingReplanGuidance = null;
        pendingReplanTrigger = null;
    }

    /** True if this run has already produced a plan that would execute identically. */
    public boolean hasSeenPlanShape(String structuralHash) {
        return seenPlanShapes.contains(structuralHash);
    }

    // ------------------------------------------------------------------ accessors

    public String id() {
        return id;
    }

    public Plan plan() {
        return plan;
    }

    public AutonomyLevel autonomyLevel() {
        return autonomyLevel;
    }

    public Object lock() {
        return lock;
    }

    public RunStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    public String stopReason() {
        return stopReason;
    }

    public boolean paused() {
        return paused;
    }

    public int replanCount() {
        return replanCount;
    }

    public String pendingReplanGuidance() {
        return pendingReplanGuidance;
    }

    public ReplanTrigger pendingReplanTrigger() {
        return pendingReplanTrigger;
    }

    public Task task(String taskId) {
        return tasksById.get(taskId);
    }

    public NodeExecution node(String taskId) {
        return nodes.get(taskId);
    }

    public Collection<NodeExecution> nodes() {
        return nodes.values();
    }

    public List<String> predecessorsOf(String taskId) {
        return predecessors.getOrDefault(taskId, List.of());
    }

    public long countInState(NodeState state) {
        return nodes.values().stream().filter(node -> node.state() == state).count();
    }

    public Long durationMs() {
        if (startedAt == null) {
            return null;
        }
        Instant end = endedAt != null ? endedAt : Instant.now();
        return java.time.Duration.between(startedAt, end).toMillis();
    }

    /**
     * Current value behind each key a task declares it reads, wherever in the graph it was produced.
     * The input half of the node's fingerprint.
     */
    public Map<String, String> resolveReadValues(Task task) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : task.reads()) {
            for (NodeExecution node : nodes.values()) {
                String value = node.outputs().get(key);
                if (value != null) {
                    resolved.put(key, value);
                    break;
                }
            }
        }
        return resolved;
    }

    /** Every task that transitively depends on any of {@code roots}, excluding the roots. */
    public Set<String> transitiveDependentsOf(Set<String> roots) {
        Set<String> dependents = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String taskId : tasksById.keySet()) {
                if (roots.contains(taskId) || dependents.contains(taskId)) {
                    continue;
                }
                boolean downstream = predecessorsOf(taskId).stream()
                        .anyMatch(id -> roots.contains(id) || dependents.contains(id));
                if (downstream) {
                    dependents.add(taskId);
                    changed = true;
                }
            }
        }
        return dependents;
    }

    /** Reverse of the plan's execution levels: undo the most recent work first. */
    public List<String> reverseExecutionOrder() {
        List<String> ordered = new ArrayList<>();
        List<List<String>> levels = plan.executionLevels();
        for (int level = levels.size() - 1; level >= 0; level--) {
            ordered.addAll(levels.get(level));
        }
        return ordered;
    }

    // ------------------------------------------------------------------ transitions

    void markStarted() {
        status = RunStatus.RUNNING;
        startedAt = Instant.now();
    }

    /** Quiesced with work outstanding: something is waiting on a human, nothing else can proceed. */
    void markAwaitingInput() {
        status = RunStatus.AWAITING_INPUT;
    }

    void resume() {
        status = RunStatus.RUNNING;
    }

    void markTerminal(RunStatus terminalStatus) {
        status = terminalStatus;
        endedAt = Instant.now();
    }

    void requestStop(String reason) {
        stopRequested = true;
        stopReason = reason;
    }

    void markPaused() {
        paused = true;
        status = RunStatus.PAUSED;
    }

    void unpause() {
        paused = false;
        status = RunStatus.RUNNING;
    }

    void holdReplan(String guidance, ReplanTrigger trigger) {
        pendingReplanGuidance = guidance;
        pendingReplanTrigger = trigger;
    }

    void clearPendingReplan() {
        pendingReplanGuidance = null;
        pendingReplanTrigger = null;
    }

    /** Rebuilds run state from storage. Persistence-layer use only. */
    public void restore(RunStatus restoredStatus, Instant restoredCreatedAt,
                        Instant restoredStartedAt, Instant restoredEndedAt,
                        boolean restoredStopRequested, String restoredStopReason,
                        boolean restoredPaused, int restoredReplanCount,
                        String restoredPendingGuidance, ReplanTrigger restoredPendingTrigger) {
        this.status = restoredStatus;
        this.createdAt = restoredCreatedAt;
        this.startedAt = restoredStartedAt;
        this.endedAt = restoredEndedAt;
        this.stopRequested = restoredStopRequested;
        this.stopReason = restoredStopReason;
        this.paused = restoredPaused;
        this.replanCount = restoredReplanCount;
        this.pendingReplanGuidance = restoredPendingGuidance;
        this.pendingReplanTrigger = restoredPendingTrigger;
    }

    /**
     * Fails every node that was mid-flight when the process died.
     *
     * @return the ids of the nodes that were interrupted
     */
    public List<String> failInterruptedNodes() {
        List<String> interrupted = new ArrayList<>();
        for (NodeExecution node : nodes.values()) {
            // RETRYING counts as interrupted: its backoff timer died with the old process, and a
            // node waiting on a wake-up that will never come would hang the run forever.
            if (node.state() == NodeState.RUNNING
                    || node.state() == NodeState.RETRYING
                    || node.state() == NodeState.COMPENSATING) {
                node.markInterruptedByRestart();
                interrupted.add(node.taskId());
            }
        }
        return interrupted;
    }
}
