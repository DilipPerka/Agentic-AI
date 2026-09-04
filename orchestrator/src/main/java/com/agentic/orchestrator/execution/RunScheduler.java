package com.agentic.orchestrator.execution;

import com.agentic.orchestrator.governance.ApprovalRepository;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.governance.Decision;
import com.agentic.orchestrator.governance.DecisionLog;
import com.agentic.orchestrator.governance.GovernanceService;
import com.agentic.orchestrator.governance.GovernanceVerdict;
import com.agentic.orchestrator.governance.Policy;
import com.agentic.orchestrator.plan.BlastRadius;
import com.agentic.orchestrator.plan.DecompositionPlanner;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import com.agentic.orchestrator.plan.Task;
import com.agentic.orchestrator.reliability.CompensationExecutor;
import com.agentic.orchestrator.reliability.CompensationResult;
import com.agentic.orchestrator.reliability.RetryPolicy;
import com.agentic.orchestrator.reliability.RetryPolicyResolver;
import com.agentic.orchestrator.replan.Fingerprinter;
import com.agentic.orchestrator.replan.PlanDiff;
import com.agentic.orchestrator.replan.PlanDiffer;
import com.agentic.orchestrator.replan.ReplanOutcome;
import com.agentic.orchestrator.replan.ReplanTrigger;
import com.agentic.orchestrator.replan.RequirementAmender;
import com.agentic.orchestrator.requirement.Requirement;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Executes a {@link Plan} as a stateful, governed dependency graph.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Event-driven, not polled.</b> {@code tick} runs when something changes.
 *   <li><b>Asynchronous.</b> Starting a run returns immediately; work proceeds on virtual threads.
 *   <li><b>Ready-set dispatch.</b> A node runs the moment <em>its own</em> predecessors succeed.
 *   <li><b>Waiting is a state, never a blocked thread.</b> Approval waits and retry backoffs hold no
 *       thread, so other branches keep running and a run can wait indefinitely at no cost.
 *   <li><b>A node cannot declare its own success.</b> The exit gate grants it, against evidence.
 *   <li><b>One monitor per run.</b> State transitions under {@code run.lock()}; executors outside.
 * </ul>
 *
 * <h2>Failure handling</h2>
 * Four distinct mechanisms, in order of escalation: bounded <b>retry</b> with backoff, a single
 * degraded <b>fallback</b> attempt, <b>compensation</b> to undo what succeeded, and <b>safe-stop</b>.
 * Compensation failure never auto-retries — it escalates to a human, because a half-undone workspace
 * is a state no automated policy should be trusted to reason about.
 *
 * <h2>Known limitations</h2>
 * <ul>
 *   <li>Timeout cancellation is best-effort: the executor thread is interrupted, but an executor
 *       that ignores interruption will keep running. It can no longer affect the node's outcome.
 *   <li>Safe-stop and pause do not interrupt running nodes; they finish first.
 * </ul>
 */
@Service
public class RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduler.class);

    /**
     * Marks an approval whose subject is the plan itself rather than a node. Plan changes go through
     * the same inbox as everything else — a separate approval mechanism would be a second thing to
     * remember to check.
     */
    public static final String PLAN_APPROVAL_PREFIX = "PLAN:v";

    private final RunRepository runs;
    private final NodeExecutor executor;
    private final CompensationExecutor compensator;
    private final EventLog events;
    private final GateEvaluator gates;
    private final GovernanceService governance;
    private final ApprovalRepository approvals;
    private final DecisionLog decisions;
    private final RetryPolicyResolver retryPolicies;
    private final PlanRepository plans;
    private final DecompositionPlanner planner;
    private final RequirementAmender amender;
    private final PlanDiffer differ;
    private final Fingerprinter fingerprinter;

    private final ExecutorService pool;
    private final ScheduledExecutorService timers;
    private final int maxConcurrency;
    private final Duration nodeTimeout;
    private final boolean fallbackEnabled;
    private final int maxReplans;
    private final int replanChurnThreshold;

    public RunScheduler(RunRepository runs,
                        NodeExecutor executor,
                        CompensationExecutor compensator,
                        EventLog events,
                        GateEvaluator gates,
                        GovernanceService governance,
                        ApprovalRepository approvals,
                        DecisionLog decisions,
                        RetryPolicyResolver retryPolicies,
                        PlanRepository plans,
                        DecompositionPlanner planner,
                        RequirementAmender amender,
                        PlanDiffer differ,
                        Fingerprinter fingerprinter,
                        @Value("${orchestrator.max-concurrency:4}") int maxConcurrency,
                        @Value("${orchestrator.node-timeout-ms:30000}") long nodeTimeoutMs,
                        @Value("${orchestrator.fallback.enabled:true}") boolean fallbackEnabled,
                        @Value("${orchestrator.replan.max-per-run:3}") int maxReplans,
                        @Value("${orchestrator.replan.churn-threshold:4}") int replanChurnThreshold) {
        this.runs = runs;
        this.executor = executor;
        this.compensator = compensator;
        this.events = events;
        this.gates = gates;
        this.governance = governance;
        this.approvals = approvals;
        this.decisions = decisions;
        this.retryPolicies = retryPolicies;
        this.plans = plans;
        this.planner = planner;
        this.amender = amender;
        this.differ = differ;
        this.fingerprinter = fingerprinter;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.nodeTimeout = Duration.ofMillis(Math.max(1, nodeTimeoutMs));
        this.fallbackEnabled = fallbackEnabled;
        this.maxReplans = Math.max(0, maxReplans);
        this.replanChurnThreshold = Math.max(1, replanChurnThreshold);
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
        this.timers = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "orchestrator-retry-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public Run start(Plan plan, AutonomyLevel autonomy) {
        return start(plan, autonomy, null);
    }

    /**
     * @param baseRunId the run whose workspace this one continues from, or {@code null}. A
     *                  brownfield change against nothing is a greenfield build wearing the wrong
     *                  label, so this is what makes the distinction real rather than cosmetic.
     */
    public Run start(Plan plan, AutonomyLevel autonomy, String baseRunId) {
        Run run = runs.save(new Run(plan, autonomy, baseRunId));

        events.append(run.id(), EventType.RUN_CREATED, null,
                "Run created from plan " + plan.id() + " with " + plan.tasks().size()
                        + " tasks across " + plan.depth() + " levels, autonomy " + autonomy
                        + (baseRunId == null ? "" : ", continuing from " + baseRunId));

        synchronized (run.lock()) {
            run.markStarted();
            events.append(run.id(), EventType.RUN_STARTED, null,
                    "Scheduling with max concurrency " + maxConcurrency);
            runs.persist(run);
        }

        tick(run);
        return run;
    }

    /**
     * Brings a run recovered from storage back under the scheduler. Nodes that were mid-flight,
     * mid-backoff or mid-compensation when the process died are failed rather than resumed.
     */
    public void recover(Run run) {
        synchronized (run.lock()) {
            for (String taskId : run.failInterruptedNodes()) {
                events.append(run.id(), EventType.NODE_FAILED, taskId, "RECOVERY",
                        "Interrupted by restart; outcome unknown");
            }
            runs.persist(run);
        }
        tick(run);
    }

    /**
     * Requests a safe stop. Pending nodes and outstanding approvals are cancelled; running nodes are
     * allowed to finish, after which the run quiesces to {@link RunStatus#STOPPED}.
     */
    public boolean stop(String runId, String reason) {
        Optional<Run> found = runs.findById(runId);
        if (found.isEmpty()) {
            return false;
        }
        Run run = found.get();

        synchronized (run.lock()) {
            if (run.status().isTerminal()) {
                return false;
            }
            run.requestStop(reason);
            events.append(run.id(), EventType.STOP_REQUESTED, null, "HUMAN",
                    "Safe-stop requested: " + reason);

            for (NodeExecution node : run.nodes()) {
                if (node.state() == NodeState.PENDING
                        || node.state() == NodeState.AWAITING_APPROVAL
                        || node.state() == NodeState.RETRYING) {
                    node.markCancelled("Cancelled by safe-stop");
                    events.append(run.id(), EventType.NODE_CANCELLED, node.taskId(),
                            "Cancelled by safe-stop");
                }
            }
            runs.persist(run);
        }

        tick(run);
        return true;
    }

    /** Holds the run. Running nodes finish; nothing new is dispatched until resumed. */
    public boolean pause(String runId, String reason) {
        Optional<Run> found = runs.findById(runId);
        if (found.isEmpty() || found.get().status().isTerminal() || found.get().paused()) {
            return false;
        }
        Run run = found.get();

        synchronized (run.lock()) {
            run.markPaused();
            events.append(run.id(), EventType.RUN_PAUSED, null, "HUMAN", "Paused: " + reason);
            runs.persist(run);
        }
        return true;
    }

    public boolean resume(String runId) {
        Optional<Run> found = runs.findById(runId);
        if (found.isEmpty() || !found.get().paused()) {
            return false;
        }
        Run run = found.get();

        synchronized (run.lock()) {
            run.unpause();
            events.append(run.id(), EventType.RUN_RESUMED, null, "HUMAN", "Resumed by operator");
            runs.persist(run);
        }

        tick(run);
        return true;
    }

    // ------------------------------------------------------------------ approvals

    public boolean approve(String approvalId, String decidedBy, String note) {
        return decide(approvalId, decidedBy, note, true);
    }

    /**
     * Rejects an approval. The node fails; its dependents block on the next tick.
     *
     * <p>{@code guidance} is recorded rather than discarded. Once re-planning exists, a rejection
     * with guidance becomes the trigger that reshapes the graph.
     */
    public boolean reject(String approvalId, String decidedBy, String guidance) {
        return decide(approvalId, decidedBy, guidance, false);
    }

    private boolean decide(String approvalId, String decidedBy, String note, boolean granted) {
        Optional<ApprovalRequest> found = approvals.findById(approvalId);
        if (found.isEmpty() || !found.get().isPending()) {
            return false;
        }
        ApprovalRequest approval = found.get();

        Optional<Run> foundRun = runs.findById(approval.runId());
        if (foundRun.isEmpty()) {
            return false;
        }
        Run run = foundRun.get();

        // A decision on a plan-level approval is not a node transition — it admits or abandons a
        // held re-plan, so it takes its own path.
        if (approval.taskId().startsWith(PLAN_APPROVAL_PREFIX)) {
            return decidePlanChange(run, approval, decidedBy, note, granted);
        }

        boolean rejectedWithGuidance = !granted && !blank(note);

        synchronized (run.lock()) {
            if (run.status().isTerminal()) {
                return false;
            }

            NodeExecution node = run.node(approval.taskId());
            String actor = "HUMAN:" + decidedBy;

            if (granted) {
                approval.approve(decidedBy, note);
                events.append(run.id(), EventType.APPROVAL_GRANTED, approval.taskId(), actor,
                        "Approved" + (blank(note) ? "" : ": " + note));
                node.markPending();
            } else {
                approval.reject(decidedBy, note);
                events.append(run.id(), EventType.APPROVAL_REJECTED, approval.taskId(), actor,
                        "Rejected" + (blank(note) ? "" : ": " + note));
                // A human rejection is not a transient fault, so it bypasses retry entirely.
                node.markFailed("Rejected by " + decidedBy + (blank(note) ? "" : ": " + note));
            }

            decisions.record(Decision.of(run.id(), approval.taskId(), actor,
                    "Should " + approval.taskId() + " proceed?",
                    granted ? "APPROVED" : "REJECTED",
                    blank(note) ? "No note supplied" : note,
                    List.of(granted ? "REJECTED" : "APPROVED")));

            if (run.status() == RunStatus.AWAITING_INPUT) {
                run.resume();
                events.append(run.id(), EventType.RUN_RESUMED, null, actor,
                        "Resumed after decision on " + approval.taskId());
            }

            approvals.save(approval);
            runs.persist(run);
        }

        // A refusal with direction is not just a failure — it is new information about what the
        // requirement should have said. Feeding it back into the planner is what makes the human
        // part of the loop rather than a gate on it.
        if (rejectedWithGuidance) {
            ReplanOutcome outcome = replan(run.id(),
                    ReplanTrigger.APPROVAL_REJECTED_WITH_GUIDANCE, note, false);
            events.append(run.id(), EventType.REPLAN_TRIGGERED, approval.taskId(),
                    "HUMAN:" + decidedBy,
                    "Rejection guidance fed back to the planner: " + outcome.status()
                            + " — " + outcome.detail());
        }

        tick(run);
        return true;
    }

    /** Admits or abandons a re-plan that was held pending human approval of the plan change. */
    private boolean decidePlanChange(Run run, ApprovalRequest approval, String decidedBy,
                                     String note, boolean granted) {
        String guidance;
        ReplanTrigger trigger;

        synchronized (run.lock()) {
            guidance = run.pendingReplanGuidance();
            trigger = run.pendingReplanTrigger();
            String actor = "HUMAN:" + decidedBy;

            if (granted) {
                approval.approve(decidedBy, note);
                events.append(run.id(), EventType.APPROVAL_GRANTED, approval.taskId(), actor,
                        "Plan change approved" + (blank(note) ? "" : ": " + note));
            } else {
                approval.reject(decidedBy, note);
                events.append(run.id(), EventType.APPROVAL_REJECTED, approval.taskId(), actor,
                        "Plan change rejected; the current plan stands"
                                + (blank(note) ? "" : ": " + note));
                run.clearPendingReplan();
            }

            decisions.record(Decision.of(run.id(), approval.taskId(), actor,
                    "Should the revised plan be adopted?", granted ? "APPROVED" : "REJECTED",
                    blank(note) ? "No note supplied" : note,
                    List.of(granted ? "Keep the current plan" : "Adopt the revised plan")));

            approvals.save(approval);
            runs.persist(run);
        }

        if (granted) {
            replan(run.id(), trigger == null ? ReplanTrigger.MANUAL : trigger, guidance, true);
        } else {
            tick(run);
        }
        return true;
    }

    // ------------------------------------------------------------------ re-planning

    /**
     * Rebuilds the graph from an amended requirement, keeping the work that is still valid (CR4.11).
     *
     * <p>The sequence, and why each step is where it is:
     * <ol>
     *   <li><b>Bound first.</b> Unbounded re-planning is a real failure mode, not a hypothetical.
     *   <li><b>Amend and re-decompose.</b> The same planner, so the shape follows from the
     *       requirement rather than from a special re-planning code path.
     *   <li><b>Diff.</b> A plan that would execute identically is not admitted — that is how
     *       oscillation starts.
     *   <li><b>Govern the plan itself.</b> A high-impact plan change raises its own approval. This is
     *       the "while maintaining governance" half of the requirement: re-planning is not a way
     *       around the controls.
     *   <li><b>Compensate before adopting.</b> Stale nodes that produced side effects are undone
     *       first, or the workspace drifts out of step with the graph describing it.
     *   <li><b>Adopt, retaining unchanged work.</b> Nodes whose fingerprint is unchanged keep their
     *       results; only what actually went stale runs again.
     * </ol>
     */
    public ReplanOutcome replan(String runId, ReplanTrigger trigger, String guidance,
                                boolean planAlreadyApproved) {
        Optional<Run> found = runs.findById(runId);
        if (found.isEmpty()) {
            return ReplanOutcome.of(ReplanOutcome.Status.REJECTED, "No such run");
        }
        Run run = found.get();

        Plan candidate;
        PlanDiff diff;
        Set<String> invalidated;

        synchronized (run.lock()) {
            if (run.status() == RunStatus.ROLLED_BACK || run.status() == RunStatus.STOPPED) {
                return ReplanOutcome.of(ReplanOutcome.Status.REJECTED,
                        "Run is " + run.status() + "; re-planning would have nothing to act on");
            }
            if (run.replanCount() >= maxReplans) {
                return ReplanOutcome.of(ReplanOutcome.Status.BOUND_REACHED,
                        "Already re-planned " + run.replanCount() + " time(s); limit is "
                                + maxReplans);
            }

            Requirement amended = amender.amend(run.plan().requirement(), guidance);
            candidate = planner.decompose(amended, run.plan().version() + 1, run.plan().id());

            diff = differ.diff(run.plan(), candidate,
                    taskId -> run.node(taskId) != null
                            && run.node(taskId).state() == NodeState.SUCCEEDED);

            if (diff.structurallyIdentical()) {
                return ReplanOutcome.of(ReplanOutcome.Status.NO_CHANGE,
                        "The amended requirement produces the same graph");
            }
            if (run.hasSeenPlanShape(candidate.structuralHash())) {
                // Two plans the run has already alternated between. Continuing would loop.
                events.append(run.id(), EventType.REPLAN_REJECTED, null,
                        "Oscillation detected: this plan shape has been produced before");
                return ReplanOutcome.of(ReplanOutcome.Status.OSCILLATION_DETECTED,
                        "This plan shape has already been produced in this run");
            }

            if (!planAlreadyApproved && diff.requiresApproval(replanChurnThreshold)) {
                String approvalId = raisePlanApproval(run, candidate, diff, guidance, trigger);
                return ReplanOutcome.awaitingApproval(diff, approvalId);
            }

            invalidated = invalidatedBy(run, candidate, diff);
        }

        // Compensation runs outside the lock: it may be slow, and holding the run's monitor through
        // it would stall every other transition on this run.
        List<String> compensated = compensateStale(run, invalidated);

        synchronized (run.lock()) {
            run.adopt(candidate, invalidated);
            plans.save(candidate);

            events.append(run.id(), EventType.PLAN_REVISED, null, "REPLANNER",
                    "Plan v" + candidate.version() + " admitted (" + diff.summary()
                            + ") after " + trigger + ". Invalidated: "
                            + (invalidated.isEmpty() ? "none" : String.join(", ", invalidated)));

            decisions.record(Decision.of(run.id(), null, "REPLANNER",
                    "Should the graph be rebuilt from the amended requirement?", "YES",
                    String.join(" ", diff.reasons(replanChurnThreshold))
                            + (compensated.isEmpty() ? ""
                            : " Undid completed work for: " + String.join(", ", compensated) + "."),
                    List.of("Keep the current plan", "Fail the run")));

            if (run.status().isTerminal() || run.status() == RunStatus.AWAITING_INPUT) {
                run.resume();
            }
            runs.persistAfterReplan(run);
        }

        tick(run);
        return ReplanOutcome.admitted(diff, candidate.id());
    }

    /**
     * Tasks that survive into the new plan but whose completed work is stale: their definition or
     * their inputs changed. Their dependents go too — work derived from a stale input is stale.
     */
    private Set<String> invalidatedBy(Run run, Plan candidate, PlanDiff diff) {
        Set<String> stale = new LinkedHashSet<>();

        for (String taskId : diff.retained()) {
            NodeExecution node = run.node(taskId);
            if (node == null || node.state() != NodeState.SUCCEEDED) {
                continue;
            }
            Task next = candidate.task(taskId);
            String current = fingerprinter.forNode(next, run.resolveReadValues(next));
            if (!current.equals(node.fingerprint())) {
                stale.add(taskId);
            }
        }

        stale.addAll(run.transitiveDependentsOf(stale));
        stale.removeIf(taskId -> candidate.task(taskId) == null);
        return stale;
    }

    /** Undoes completed side effects of stale and removed nodes, most recent first. */
    private List<String> compensateStale(Run run, Set<String> invalidated) {
        List<String> compensated = new ArrayList<>();

        for (String taskId : run.reverseExecutionOrder()) {
            NodeExecution node = run.node(taskId);
            boolean needsUndo = node != null
                    && node.state() == NodeState.SUCCEEDED
                    && !node.outputs().isEmpty()
                    && invalidated.contains(taskId);
            if (!needsUndo) {
                continue;
            }

            CompensationResult result = compensateSafely(run, node);
            synchronized (run.lock()) {
                if (result.success()) {
                    events.append(run.id(), EventType.NODE_ROLLED_BACK, taskId,
                            "Undone before re-planning: " + result.summary());
                    compensated.add(taskId);
                } else {
                    // The node is about to be re-run over a workspace we could not clean up.
                    // Recorded loudly rather than silently proceeding.
                    events.append(run.id(), EventType.COMPENSATION_FAILED, taskId,
                            "Could not undo before re-planning: " + result.summary());
                }
            }
        }
        return compensated;
    }

    private String raisePlanApproval(Run run, Plan candidate, PlanDiff diff, String guidance,
                                     ReplanTrigger trigger) {
        run.holdReplan(guidance, trigger);

        String subject = PLAN_APPROVAL_PREFIX + candidate.version();
        ApprovalRequest existing = approvals.findPendingFor(run.id(), subject).orElse(null);
        if (existing != null) {
            return existing.id();
        }

        BlastRadius radius = diff.highestAddedBlastRadius() == null
                ? BlastRadius.MEDIUM
                : diff.highestAddedBlastRadius();

        ApprovalRequest request = approvals.save(new ApprovalRequest(
                run.id(), subject, "Adopt plan v" + candidate.version(), radius,
                diff.reasons(replanChurnThreshold), List.of("REPLAN-GOVERNANCE")));

        events.append(run.id(), EventType.PLAN_APPROVAL_REQUESTED, subject, "REPLANNER",
                "Plan change (" + diff.summary() + ") is high-impact and needs approval");

        if (run.status() != RunStatus.AWAITING_INPUT && !run.status().isTerminal()) {
            run.markAwaitingInput();
            events.append(run.id(), EventType.RUN_AWAITING_INPUT, null,
                    "Awaiting approval of a plan change");
        }
        runs.persist(run);
        return request.id();
    }

    // ------------------------------------------------------------------ rollback

    /**
     * Undoes every succeeded node, most recent first.
     *
     * <p>Only permitted once the run has stopped moving. Compensating a node while a dependent is
     * still executing against its output would corrupt both.
     *
     * <p>Compensation failure halts the rollback immediately and leaves the node BLOCKED. Continuing
     * past a failed undo would compound a known-partial state with more changes, and no automated
     * policy should decide what to do about that.
     */
    public boolean rollback(String runId, String reason) {
        Optional<Run> found = runs.findById(runId);
        if (found.isEmpty()) {
            return false;
        }
        Run run = found.get();

        synchronized (run.lock()) {
            boolean settled = run.status().isTerminal() || run.status() == RunStatus.PAUSED;
            if (!settled || run.status() == RunStatus.ROLLED_BACK) {
                return false;
            }
        }

        boolean complete = true;
        for (String taskId : run.reverseExecutionOrder()) {
            NodeExecution node = run.node(taskId);
            if (node == null || node.state() != NodeState.SUCCEEDED) {
                continue;
            }

            synchronized (run.lock()) {
                node.markCompensating();
                events.append(run.id(), EventType.NODE_COMPENSATING, taskId, "Undoing " + taskId);
                runs.persist(run);
            }

            CompensationResult result = compensateSafely(run, node);

            synchronized (run.lock()) {
                if (result.success()) {
                    node.markRolledBack(result.summary());
                    events.append(run.id(), EventType.NODE_ROLLED_BACK, taskId, result.summary());
                } else {
                    node.markBlocked(result.summary());
                    events.append(run.id(), EventType.COMPENSATION_FAILED, taskId,
                            result.summary() + " — rollback halted, human review required");
                    decisions.record(Decision.of(run.id(), taskId, "SCHEDULER",
                            "Can the rollback continue past a failed compensation?", "NO",
                            "The workspace is in a partial state that no automated policy should "
                                    + "reason about. " + result.summary(),
                            List.of("Continue undoing later nodes", "Retry the compensation")));
                    complete = false;
                }
                runs.persist(run);
            }

            if (!complete) {
                break;
            }
        }

        synchronized (run.lock()) {
            if (complete) {
                run.markTerminal(RunStatus.ROLLED_BACK);
                events.append(run.id(), EventType.RUN_ROLLED_BACK, null, "HUMAN",
                        "Rollback complete: " + reason);
            } else {
                run.markTerminal(RunStatus.FAILED);
                events.append(run.id(), EventType.RUN_FAILED, null,
                        "Rollback incomplete; workspace requires human review");
            }
            runs.persist(run);
        }
        return complete;
    }

    private CompensationResult compensateSafely(Run run, NodeExecution node) {
        try {
            CompensationResult result = compensator.compensate(new ExecutionContext(
                    run.id(), run.task(node.taskId()), node.attempt(), false,
                    upstreamOutputs(run, node),
                    // The node's own outputs: undoing work needs to know what that work produced.
                    node.outputs()));
            return result == null
                    ? CompensationResult.failed("Compensator returned no result")
                    : result;
        } catch (Throwable failure) {
            log.error("Compensation threw for {}/{}", run.id(), node.taskId(), failure);
            return CompensationResult.failed(
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    // ------------------------------------------------------------------ scheduling

    private void tick(Run run) {
        List<NodeExecution> dispatch = new ArrayList<>();

        synchronized (run.lock()) {
            if (run.status().isTerminal()) {
                return;
            }

            blockUnreachableNodes(run);

            if (!run.stopRequested() && !run.paused()) {
                int slots = (int) (maxConcurrency - run.countInState(NodeState.RUNNING));
                for (NodeExecution node : run.nodes()) {
                    if (slots <= 0) {
                        break;
                    }
                    if (node.state() != NodeState.PENDING || !predecessorsSucceeded(run, node)) {
                        continue;
                    }
                    if (admit(run, node)) {
                        dispatch.add(node);
                        slots--;
                    }
                }
            }

            if (dispatch.isEmpty() && run.countInState(NodeState.RUNNING) == 0) {
                quiesce(run);
                runs.persist(run);
                return;
            }
            runs.persist(run);
        }

        for (NodeExecution node : dispatch) {
            pool.execute(() -> executeNode(run, node));
        }
    }

    private boolean admit(Run run, NodeExecution node) {
        Task task = run.task(node.taskId());
        GovernanceVerdict verdict = governance.evaluate(task, run.autonomyLevel());

        boolean approvalGranted = approvals.findDecidedFor(run.id(), node.taskId())
                .map(request -> request.status() == ApprovalRequest.Status.APPROVED)
                .orElse(false);

        GateResult gate = gates.entryGate(task, true, verdict, approvalGranted, !run.stopRequested());
        if (gate.satisfied()) {
            node.markRunning();
            events.append(run.id(), EventType.NODE_STARTED, node.taskId(),
                    task.agentRole().name(),
                    "Started (attempt " + node.attempt()
                            + (node.fallbackAttempted() ? ", degraded fallback" : "") + ")");
            return true;
        }

        if (verdict.denied()) {
            node.markBlocked("Policy " + verdict.denyingPolicy() + " denied this action");
            events.append(run.id(), EventType.POLICY_DENIED, node.taskId(),
                    "POLICY:" + verdict.denyingPolicy(), verdict.summary());
            decisions.record(Decision.of(run.id(), node.taskId(),
                    "POLICY:" + verdict.denyingPolicy(),
                    "May " + node.taskId() + " proceed?", "DENIED", verdict.summary(),
                    List.of("Allow with approval", "Allow unattended")));
            return false;
        }

        if (verdict.requiresApproval() && !approvalGranted) {
            raiseApproval(run, node, task, verdict);
            return false;
        }

        events.append(run.id(), EventType.GATE_FAILED, node.taskId(), gate.summary());
        return false;
    }

    private void raiseApproval(Run run, NodeExecution node, Task task, GovernanceVerdict verdict) {
        node.markAwaitingApproval("Awaiting human approval");
        events.append(run.id(), EventType.NODE_AWAITING_APPROVAL, node.taskId(),
                task.agentRole().name(), verdict.summary());

        // One open request per node: the entry gate is re-evaluated on every tick, and without this
        // guard a single decision would generate a new inbox item each time.
        if (approvals.findPendingFor(run.id(), node.taskId()).isEmpty()) {
            ApprovalRequest request = approvals.save(new ApprovalRequest(
                    run.id(), node.taskId(), task.title(), task.blastRadius(),
                    verdict.reasons(), verdict.triggered().stream().map(Policy::id).toList()));

            events.append(run.id(), EventType.APPROVAL_REQUESTED, node.taskId(),
                    "SCHEDULER", "Approval " + request.id() + " raised: " + verdict.summary());
        }
    }

    private void blockUnreachableNodes(Run run) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NodeExecution node : run.nodes()) {
                if (node.state() != NodeState.PENDING
                        && node.state() != NodeState.AWAITING_APPROVAL
                        && node.state() != NodeState.RETRYING) {
                    continue;
                }
                for (String predecessorId : run.predecessorsOf(node.taskId())) {
                    NodeState predecessorState = run.node(predecessorId).state();
                    if (predecessorState.isUnsuccessfulTerminal()) {
                        String reason = "Upstream " + predecessorId + " ended " + predecessorState;
                        node.markBlocked(reason);
                        events.append(run.id(), EventType.NODE_BLOCKED, node.taskId(), reason);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    private boolean predecessorsSucceeded(Run run, NodeExecution node) {
        return run.predecessorsOf(node.taskId()).stream()
                .allMatch(id -> run.node(id).state() == NodeState.SUCCEEDED);
    }

    // ------------------------------------------------------------------ node execution

    private void executeNode(Run run, NodeExecution node) {
        Task task = run.task(node.taskId());
        boolean degraded = node.fallbackAttempted();

        ExecutionContext context = new ExecutionContext(
                run.id(), task, node.attempt(), degraded, upstreamOutputs(run, node),
                Map.of(), run.baseRunId());

        NodeResult result = runWithTimeout(run, node, context);
        GateResult exitGate = gates.exitGate(task, result);

        synchronized (run.lock()) {
            if (exitGate.satisfied()) {
                recordSuccess(run, node, task, result, degraded);
            } else {
                handleFailure(run, node, task, result, exitGate);
            }
            runs.persist(run);
        }

        tick(run);
    }

    /**
     * Runs the executor with a deadline.
     *
     * <p>Cancellation is best-effort: the thread is interrupted, but an executor that ignores
     * interruption keeps running. It cannot affect the node's outcome afterwards, which is the
     * property that actually matters — a hung executor must not hang the run.
     */
    private NodeResult runWithTimeout(Run run, NodeExecution node, ExecutionContext context) {
        Future<NodeResult> future = pool.submit(() -> executor.execute(context));
        try {
            NodeResult result = future.get(nodeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return result == null ? NodeResult.failure("Executor returned no result") : result;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            events.append(run.id(), EventType.NODE_TIMED_OUT, node.taskId(),
                    "Exceeded " + nodeTimeout.toMillis() + "ms");
            return NodeResult.failure("Timed out after " + nodeTimeout.toMillis() + "ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return NodeResult.failure("Interrupted");
        } catch (Exception failure) {
            // Whatever an executor throws, the node must reach a terminal state. A node stuck in
            // RUNNING means the scheduler never ticks again and the run hangs with no diagnosis.
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            log.error("Executor threw for {}/{}", run.id(), node.taskId(), cause);
            return NodeResult.failure(
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private void recordSuccess(Run run, NodeExecution node, Task task, NodeResult result,
                               boolean degraded) {
        // Captured at the moment of success, under the run's monitor, so it reflects exactly the
        // inputs this result was produced from. Re-planning later compares against a fresh one.
        node.markSucceeded(result, degraded,
                fingerprinter.forNode(task, run.resolveReadValues(task)));
        events.append(run.id(), EventType.NODE_SUCCEEDED, node.taskId(),
                task.agentRole().name(), result.summary() + " (" + node.durationMs() + "ms)");

        if (degraded) {
            events.append(run.id(), EventType.NODE_DEGRADED, node.taskId(),
                    "Succeeded only via the degraded fallback path");
            decisions.record(Decision.of(run.id(), node.taskId(), "SCHEDULER",
                    "Accept " + node.taskId() + " completed in degraded mode?", "YES",
                    "Retries were exhausted and the fallback produced acceptable evidence. The "
                            + "output is weaker than intended and should be reviewed before release.",
                    List.of("Fail the node", "Escalate to a human immediately")));
        }
    }

    /** Escalation ladder: retry, then one degraded fallback, then fail. */
    private void handleFailure(Run run, NodeExecution node, Task task, NodeResult result,
                               GateResult exitGate) {
        RetryPolicy policy = retryPolicies.policyFor(task);
        boolean runIsAccepting = !run.stopRequested() && !run.paused();

        if (result.retryable() && runIsAccepting && policy.allowsAnotherAttempt(node.attempt())) {
            Duration backoff = policy.backoffAfter(node.attempt());
            node.markRetrying(exitGate.summary());
            events.append(run.id(), EventType.RETRY_SCHEDULED, node.taskId(),
                    task.agentRole().name(),
                    "Attempt " + node.attempt() + " of " + policy.maxAttempts()
                            + " failed; retrying in " + backoff.toMillis() + "ms. "
                            + exitGate.summary());
            scheduleRetry(run, node, backoff);
            return;
        }

        if (result.retryable() && runIsAccepting && fallbackEnabled && !node.fallbackAttempted()
                && policy.maxAttempts() > 1) {
            events.append(run.id(), EventType.RETRIES_EXHAUSTED, node.taskId(),
                    "All " + policy.maxAttempts() + " attempts failed");
            node.markAwaitingFallback("Retries exhausted; attempting degraded fallback");
            events.append(run.id(), EventType.FALLBACK_ATTEMPTED, node.taskId(),
                    task.agentRole().name(), "Falling back to a degraded attempt");
            return;
        }

        node.markFailed(exitGate.summary());
        events.append(run.id(), EventType.NODE_FAILED, node.taskId(),
                task.agentRole().name(), exitGate.summary());

        if (result.success()) {
            // The executor claimed success and the gate refused it. Worth its own decision record:
            // this is the rule doing its job, not an ordinary failure.
            decisions.record(Decision.of(run.id(), node.taskId(), "GATE:ExitGate",
                    "Did " + node.taskId() + " actually succeed?", "NO", exitGate.summary(),
                    List.of("Accept the executor's claim")));
        }
    }

    /**
     * Arms the backoff timer. The node holds no thread while it waits — the timer is one shared
     * daemon thread for the whole orchestrator, not one per waiting node.
     */
    private void scheduleRetry(Run run, NodeExecution node, Duration backoff) {
        timers.schedule(() -> {
            synchronized (run.lock()) {
                if (node.state() != NodeState.RETRYING || run.status().isTerminal()) {
                    return; // Cancelled, stopped or superseded while the timer was pending.
                }
                node.markPending();
                runs.persist(run);
            }
            tick(run);
        }, Math.max(1, backoff.toMillis()), TimeUnit.MILLISECONDS);
    }

    private Map<String, String> upstreamOutputs(Run run, NodeExecution node) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String predecessorId : run.predecessorsOf(node.taskId())) {
            run.node(predecessorId).outputs()
                    .forEach((key, value) -> merged.put(predecessorId + "." + key, value));
        }
        return merged;
    }

    private void quiesce(Run run) {
        if (run.paused()) {
            return; // Held by an operator; not finished, not waiting on a decision.
        }

        boolean awaitingHuman = run.countInState(NodeState.AWAITING_APPROVAL) > 0;
        boolean retrying = run.countInState(NodeState.RETRYING) > 0;

        if (retrying && !run.stopRequested()) {
            return; // A backoff timer is armed; the run is not idle, just waiting.
        }

        if (awaitingHuman && !run.stopRequested()) {
            if (run.status() != RunStatus.AWAITING_INPUT) {
                run.markAwaitingInput();
                events.append(run.id(), EventType.RUN_AWAITING_INPUT, null,
                        run.countInState(NodeState.AWAITING_APPROVAL)
                                + " node(s) awaiting approval; no other branch can progress");
            }
            return;
        }

        RunStatus status;
        EventType eventType;
        if (run.stopRequested()) {
            status = RunStatus.STOPPED;
            eventType = EventType.RUN_STOPPED;
        } else if (run.nodes().stream().allMatch(node -> node.state() == NodeState.SUCCEEDED)) {
            status = RunStatus.COMPLETED;
            eventType = EventType.RUN_COMPLETED;
        } else {
            status = RunStatus.FAILED;
            eventType = EventType.RUN_FAILED;
        }

        run.markTerminal(status);
        events.append(run.id(), eventType, null, summarise(run));
    }

    private String summarise(Run run) {
        return "succeeded=" + run.countInState(NodeState.SUCCEEDED)
                + " failed=" + run.countInState(NodeState.FAILED)
                + " blocked=" + run.countInState(NodeState.BLOCKED)
                + " cancelled=" + run.countInState(NodeState.CANCELLED)
                + " durationMs=" + run.durationMs();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    void shutdown() {
        timers.shutdownNow();
        pool.shutdown();
    }
}
