package com.agentic.orchestrator.replan;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.EventType;
import com.agentic.orchestrator.execution.ExecutionContext;
import com.agentic.orchestrator.execution.GateEvaluator;
import com.agentic.orchestrator.execution.NodeExecutor;
import com.agentic.orchestrator.execution.NodeResult;
import com.agentic.orchestrator.execution.NodeState;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunRepository;
import com.agentic.orchestrator.execution.RunScheduler;
import com.agentic.orchestrator.execution.RunStatus;
import com.agentic.orchestrator.governance.ApprovalMatrix;
import com.agentic.orchestrator.governance.ApprovalRepository;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.governance.DecisionLog;
import com.agentic.orchestrator.governance.GovernanceService;
import com.agentic.orchestrator.governance.PolicyEngine;
import com.agentic.orchestrator.persistence.StatePersistence;
import com.agentic.orchestrator.plan.AgentRole;
import com.agentic.orchestrator.plan.BlastRadius;
import com.agentic.orchestrator.plan.DecompositionPlanner;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import com.agentic.orchestrator.plan.Stage;
import com.agentic.orchestrator.plan.Task;
import com.agentic.orchestrator.reliability.CompensationExecutor;
import com.agentic.orchestrator.reliability.CompensationResult;
import com.agentic.orchestrator.reliability.RetryPolicyResolver;
import com.agentic.orchestrator.requirement.CapabilityCatalog;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** CR4.11: dynamic re-planning when upstream outputs or the requirement change. */
class ReplanningTest {

    private static final String AMBIGUOUS = "Make the URL shortener more reliable and faster";
    private static final String GREENFIELD =
            "Build a URL shortener service with shorten and redirect APIs";

    /**
     * Observability is the one capability in the catalogue that needs no schema change and does not
     * touch the request hot path, so adding it produces a plan change that governance lets through
     * unattended. Tests about the mechanics of re-planning use it deliberately; the governance path
     * is exercised separately, where it is the subject rather than a side effect.
     */
    private static final String LOW_RISK_GUIDANCE = "Add monitoring and health checks";

    private final RunRepository runs = new RunRepository(StatePersistence.NONE);
    private final EventLog events = new EventLog(StatePersistence.NONE);
    private final ApprovalRepository approvals = new ApprovalRepository(StatePersistence.NONE);
    private final DecisionLog decisions = new DecisionLog(StatePersistence.NONE);
    private final PlanRepository plans = new PlanRepository(StatePersistence.NONE);
    private final RequirementNormalizer normalizer =
            new RequirementNormalizer(new CapabilityCatalog());
    private final DecompositionPlanner planner = new DecompositionPlanner();
    private final Fingerprinter fingerprinter = new Fingerprinter();
    private final PlanDiffer differ = new PlanDiffer();

    private final List<String> compensated = new CopyOnWriteArrayList<>();
    private final CompensationExecutor recorder = context -> {
        compensated.add(context.task().id());
        return CompensationResult.undone("undid " + context.task().id());
    };

    private RunScheduler scheduler(int maxReplans, int churnThreshold) {
        return new RunScheduler(runs, succeeds(), recorder, events, new GateEvaluator(),
                new GovernanceService(new ApprovalMatrix(), new PolicyEngine()),
                approvals, decisions, new RetryPolicyResolver(1, 0),
                plans, planner,
                new RequirementAmender(normalizer), differ, fingerprinter,
                4, 30_000, false, maxReplans, churnThreshold);
    }

    private static NodeExecutor succeeds() {
        return context -> NodeResult.success("ok", context.task().writes().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key -> "produced")));
    }

    private Plan planFor(String requirement) {
        return plans.save(planner.decompose(normalizer.normalize(requirement)));
    }

    private static void awaitSettled(Run run) {
        await().atMost(20, SECONDS).until(() ->
                run.status().isTerminal() || run.status() == RunStatus.AWAITING_INPUT);
    }

    /** Approves everything the run asks for except plan-level changes. */
    private void approveNodeApprovals(RunScheduler scheduler, Run run) {
        approvals.findByRun(run.id()).stream()
                .filter(ApprovalRequest::isPending)
                .filter(request -> !request.taskId().startsWith(RunScheduler.PLAN_APPROVAL_PREFIX))
                .forEach(request -> scheduler.approve(request.id(), "tester", "ok"));
    }

    // ------------------------------------------------------------------ fingerprints

    @Nested
    @DisplayName("fingerprints")
    class Fingerprints {

        private Task task(String id, Set<String> reads) {
            return new Task(id, "Task " + id, id, Stage.IMPLEMENTATION, AgentRole.IMPLEMENTER,
                    BlastRadius.LOW, List.of("done"), reads, Set.of(id + ":out"), false);
        }

        @Test
        @DisplayName("identical definition and inputs give an identical fingerprint")
        void isStableAcrossCalls() {
            Task task = task("A", Set.of("in:one", "in:two"));
            Map<String, String> inputs = Map.of("in:one", "v1", "in:two", "v2");

            assertThat(fingerprinter.forNode(task, inputs))
                    .isEqualTo(fingerprinter.forNode(task, inputs));
        }

        @Test
        @DisplayName("map ordering does not change the fingerprint")
        void isOrderIndependent() {
            Task task = task("A", Set.of("in:one", "in:two"));

            java.util.LinkedHashMap<String, String> forward = new java.util.LinkedHashMap<>();
            forward.put("in:one", "v1");
            forward.put("in:two", "v2");
            java.util.LinkedHashMap<String, String> reverse = new java.util.LinkedHashMap<>();
            reverse.put("in:two", "v2");
            reverse.put("in:one", "v1");

            assertThat(fingerprinter.forNode(task, forward))
                    .isEqualTo(fingerprinter.forNode(task, reverse));
        }

        @Test
        @DisplayName("a changed input value changes the fingerprint")
        void tracksInputChanges() {
            Task task = task("A", Set.of("in:one"));

            assertThat(fingerprinter.forNode(task, Map.of("in:one", "v1")))
                    .isNotEqualTo(fingerprinter.forNode(task, Map.of("in:one", "v2")));
        }

        @Test
        @DisplayName("a changed task definition changes the fingerprint")
        void tracksDefinitionChanges() {
            Map<String, String> inputs = Map.of("in:one", "v1");

            Task original = task("A", Set.of("in:one"));
            Task widened = new Task("A", "Task A", "A", Stage.IMPLEMENTATION, AgentRole.IMPLEMENTER,
                    BlastRadius.HIGH, List.of("done"), Set.of("in:one"), Set.of("A:out"), false);

            assertThat(fingerprinter.forNode(original, inputs))
                    .isNotEqualTo(fingerprinter.forNode(widened, inputs));
        }
    }

    // ------------------------------------------------------------------ plan diff

    @Nested
    @DisplayName("plan diff")
    class Diffing {

        @Test
        @DisplayName("identifies what was added, removed and retained")
        void classifiesChanges() {
            Plan before = planner.decompose(normalizer.normalize(GREENFIELD));
            Plan after = planner.decompose(normalizer.normalize(
                    GREENFIELD + " with click analytics"), 2, before.id());

            PlanDiff diff = differ.diff(before, after, taskId -> false);

            assertThat(diff.added()).contains("IMPL_CLICK_ANALYTICS", "DESIGN_CLICK_ANALYTICS");
            assertThat(diff.retained()).contains("REQ_NORMALISE", "IMPL_LINK_CREATION");
            assertThat(diff.removed()).isEmpty();
            assertThat(diff.structurallyIdentical()).isFalse();
        }

        @Test
        @DisplayName("two plans that would execute identically hash identically")
        void detectsStructuralIdentity() {
            Plan first = planner.decompose(normalizer.normalize(GREENFIELD));
            Plan second = planner.decompose(normalizer.normalize(GREENFIELD), 2, first.id());

            // Different ids and timestamps, same graph.
            assertThat(first.id()).isNotEqualTo(second.id());
            assertThat(differ.diff(first, second, taskId -> false).structurallyIdentical()).isTrue();
        }

        @Test
        @DisplayName("removing completed work needs approval regardless of size")
        void removalOfCompletedWorkRequiresApproval() {
            Plan before = planner.decompose(normalizer.normalize(
                    GREENFIELD + " with click analytics"));
            Plan after = planner.decompose(normalizer.normalize(GREENFIELD), 2, before.id());

            PlanDiff diff = differ.diff(before, after,
                    taskId -> taskId.equals("IMPL_CLICK_ANALYTICS"));

            assertThat(diff.removedWithCompletedWork()).contains("IMPL_CLICK_ANALYTICS");
            assertThat(diff.requiresApproval(100)).isTrue();
        }

        @Test
        @DisplayName("a small, low-risk change does not need approval")
        void smallSafeChangesPassThrough() {
            Plan before = planner.decompose(normalizer.normalize(GREENFIELD));
            Plan after = planner.decompose(normalizer.normalize(
                    GREENFIELD + " with observability"), 2, before.id());

            PlanDiff diff = differ.diff(before, after, taskId -> false);

            assertThat(diff.highestAddedBlastRadius()).isNotIn(
                    BlastRadius.HIGH, BlastRadius.CRITICAL);
            assertThat(diff.requiresApproval(100)).isFalse();
        }
    }

    // ------------------------------------------------------------------ end to end

    @Nested
    @DisplayName("re-planning a run")
    class Replanning {

        @Test
        @DisplayName("clarifying an ambiguous requirement turns 2 tasks into a full graph")
        void clarificationExpandsTheGraph() {
            RunScheduler scheduler = scheduler(3, 100);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            // The ambiguous plan halts at CLARIFY: two tasks, nothing invented.
            assertThat(run.plan().tasks()).hasSize(2);
            assertThat(run.status()).isEqualTo(RunStatus.AWAITING_INPUT);

            ReplanOutcome outcome = scheduler.replan(run.id(), ReplanTrigger.REQUIREMENT_AMENDED,
                    LOW_RISK_GUIDANCE, false);

            assertThat(outcome.status()).isEqualTo(ReplanOutcome.Status.ADMITTED);
            assertThat(run.plan().version()).isEqualTo(2);
            assertThat(run.plan().tasks().size()).isGreaterThan(5);
            assertThat(run.replanCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("completed work that is still valid is kept, not redone")
        void retainsUnchangedWork() {
            RunScheduler scheduler = scheduler(3, 100);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            // REQ_NORMALISE succeeded before the run parked on CLARIFY.
            assertThat(run.node("REQ_NORMALISE").state()).isEqualTo(NodeState.SUCCEEDED);
            var completedAt = run.node("REQ_NORMALISE").endedAt();

            scheduler.replan(run.id(), ReplanTrigger.REQUIREMENT_AMENDED,
                    LOW_RISK_GUIDANCE, false);

            // Same node object, same completion time: it was carried over, not re-executed.
            assertThat(run.node("REQ_NORMALISE").state()).isEqualTo(NodeState.SUCCEEDED);
            assertThat(run.node("REQ_NORMALISE").endedAt()).isEqualTo(completedAt);
        }

        @Test
        @DisplayName("the new plan names the one it replaced")
        void plansFormALineage() {
            RunScheduler scheduler = scheduler(3, 100);
            Plan original = planFor(AMBIGUOUS);
            Run run = scheduler.start(original, AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            scheduler.replan(run.id(), ReplanTrigger.MANUAL, LOW_RISK_GUIDANCE, false);

            assertThat(run.plan().supersedes()).isEqualTo(original.id());
            assertThat(run.plan().version()).isEqualTo(2);
        }

        @Test
        @DisplayName("re-planning is recorded as an event and a decision")
        void isAudited() {
            RunScheduler scheduler = scheduler(3, 100);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            scheduler.replan(run.id(), ReplanTrigger.MANUAL, LOW_RISK_GUIDANCE, false);

            assertThat(events.forRun(run.id()))
                    .anyMatch(event -> event.type() == EventType.PLAN_REVISED);
            assertThat(decisions.forRun(run.id()))
                    .anySatisfy(decision -> assertThat(decision.actor()).isEqualTo("REPLANNER"));
        }

        @Test
        @DisplayName("a re-plan producing the same graph is refused")
        void identicalPlansAreNotAdmitted() {
            RunScheduler scheduler = scheduler(3, 100);
            Run run = scheduler.start(planFor(GREENFIELD), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            ReplanOutcome outcome = scheduler.replan(run.id(), ReplanTrigger.MANUAL,
                    "please make it good", false);

            assertThat(outcome.status()).isEqualTo(ReplanOutcome.Status.NO_CHANGE);
            assertThat(run.replanCount()).isZero();
        }
    }

    // ------------------------------------------------------------------ bounds

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("re-planning is capped per run")
        void respectsTheReplanBound() {
            RunScheduler scheduler = scheduler(1, 100);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            // planAlreadyApproved: these tests are about the bound. Routing each attempt through the
            // approval flow would exercise governance twice and the bound not at all.
            assertThat(scheduler.replan(run.id(), ReplanTrigger.MANUAL, LOW_RISK_GUIDANCE, true)
                    .status()).isEqualTo(ReplanOutcome.Status.ADMITTED);

            ReplanOutcome second = scheduler.replan(run.id(), ReplanTrigger.MANUAL,
                    "Add click analytics", true);

            assertThat(second.status()).isEqualTo(ReplanOutcome.Status.BOUND_REACHED);
            assertThat(second.detail()).contains("limit is 1");
        }

        @Test
        @DisplayName("the run remembers every plan shape it has produced")
        void remembersPlanShapes() {
            RunScheduler scheduler = scheduler(5, 100);
            Plan first = planFor(AMBIGUOUS);
            Run run = scheduler.start(first, AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            assertThat(run.hasSeenPlanShape(first.structuralHash())).isTrue();

            scheduler.replan(run.id(), ReplanTrigger.MANUAL, LOW_RISK_GUIDANCE, true);
            awaitSettled(run);

            // Both shapes are remembered, so a planner that flips back to either is caught. Identity
            // comparison would miss it — a regenerated plan gets a fresh id and timestamp — which is
            // the structural hash earning its keep.
            assertThat(run.hasSeenPlanShape(first.structuralHash())).isTrue();
            assertThat(run.hasSeenPlanShape(run.plan().structuralHash())).isTrue();
        }

        @Test
        @DisplayName("guidance that produces the same graph again is refused, not re-admitted")
        void repeatedGuidanceIsRefused() {
            RunScheduler scheduler = scheduler(5, 100);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            scheduler.replan(run.id(), ReplanTrigger.MANUAL, LOW_RISK_GUIDANCE, true);
            awaitSettled(run);

            ReplanOutcome repeated = scheduler.replan(run.id(), ReplanTrigger.MANUAL,
                    LOW_RISK_GUIDANCE, true);

            assertThat(repeated.status()).isIn(
                    ReplanOutcome.Status.NO_CHANGE, ReplanOutcome.Status.OSCILLATION_DETECTED);
            assertThat(run.replanCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ governance

    @Nested
    @DisplayName("governed plan admission")
    class GovernedAdmission {

        @Test
        @DisplayName("a high-impact plan change raises an approval on the plan itself")
        void highImpactChangesNeedApproval() {
            // Threshold of 1: any real change is high-impact, which is the point being tested.
            RunScheduler scheduler = scheduler(3, 1);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            ReplanOutcome outcome = scheduler.replan(run.id(), ReplanTrigger.MANUAL,
                    "Add caching and rate limiting", false);

            assertThat(outcome.status()).isEqualTo(ReplanOutcome.Status.AWAITING_PLAN_APPROVAL);
            // The plan was NOT adopted while the decision is outstanding.
            assertThat(run.plan().version()).isEqualTo(1);
            assertThat(run.plan().tasks()).hasSize(2);

            ApprovalRequest planApproval = approvals.findByRun(run.id()).stream()
                    .filter(request -> request.taskId()
                            .startsWith(RunScheduler.PLAN_APPROVAL_PREFIX))
                    .findFirst().orElseThrow();
            assertThat(planApproval.reasons())
                    .anySatisfy(reason -> assertThat(reason).contains("Added:"));
        }

        @Test
        @DisplayName("approving the plan change admits it")
        void approvingThePlanAdmitsIt() {
            RunScheduler scheduler = scheduler(3, 1);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);
            scheduler.replan(run.id(), ReplanTrigger.MANUAL, "Add caching", false);

            ApprovalRequest planApproval = approvals.findByRun(run.id()).stream()
                    .filter(request -> request.taskId()
                            .startsWith(RunScheduler.PLAN_APPROVAL_PREFIX))
                    .findFirst().orElseThrow();

            assertThat(scheduler.approve(planApproval.id(), "sravan", "shape looks right")).isTrue();

            assertThat(run.plan().version()).isEqualTo(2);
            assertThat(run.plan().tasks().size()).isGreaterThan(2);
        }

        @Test
        @DisplayName("rejecting the plan change leaves the current plan standing")
        void rejectingThePlanKeepsTheCurrentOne() {
            RunScheduler scheduler = scheduler(3, 1);
            Run run = scheduler.start(planFor(AMBIGUOUS), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);
            scheduler.replan(run.id(), ReplanTrigger.MANUAL, "Add caching", false);

            ApprovalRequest planApproval = approvals.findByRun(run.id()).stream()
                    .filter(request -> request.taskId()
                            .startsWith(RunScheduler.PLAN_APPROVAL_PREFIX))
                    .findFirst().orElseThrow();

            scheduler.reject(planApproval.id(), "sravan", null);

            assertThat(run.plan().version()).isEqualTo(1);
            assertThat(run.replanCount()).isZero();
            assertThat(run.pendingReplanGuidance()).isNull();
        }
    }

    // ------------------------------------------------------------------ the loop

    @Nested
    @DisplayName("rejection with guidance")
    class RejectionFeedsBack {

        @Test
        @DisplayName("refusing an approval with direction reshapes the graph")
        void guidanceTriggersAReplan() {
            RunScheduler scheduler = scheduler(3, 100);
            Run run = scheduler.start(planFor(GREENFIELD), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            ApprovalRequest pending = approvals.findPendingFor(run.id(), "SCHEMA_LINK_CREATION")
                    .orElseThrow();

            // The human refuses, and says what they want instead. That second part is the whole
            // difference between a gate and a participant.
            scheduler.reject(pending.id(), "sravan", LOW_RISK_GUIDANCE);

            await().atMost(20, SECONDS).until(() -> run.plan().version() == 2);

            assertThat(run.plan().tasks()).extracting(Task::id)
                    .contains("IMPL_OBSERVABILITY");
            assertThat(events.forRun(run.id()))
                    .anyMatch(event -> event.type() == EventType.REPLAN_TRIGGERED);

            // The rejected node did not stay dead. Re-planning reset it so the revised graph can
            // actually run, rather than changing shape and blocking on the same node.
            assertThat(run.node("SCHEMA_LINK_CREATION").state())
                    .isNotEqualTo(NodeState.FAILED);
        }

        @Test
        @DisplayName("a refusal with no direction just fails the node")
        void rejectionWithoutGuidanceDoesNotReplan() {
            RunScheduler scheduler = scheduler(3, 100);
            Run run = scheduler.start(planFor(GREENFIELD), AutonomyLevel.L2_DELEGATED);
            awaitSettled(run);

            ApprovalRequest pending = approvals.findPendingFor(run.id(), "SCHEMA_LINK_CREATION")
                    .orElseThrow();
            scheduler.reject(pending.id(), "sravan", null);

            await().atMost(20, SECONDS).until(() -> run.status().isTerminal());

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.plan().version()).isEqualTo(1);
            assertThat(run.replanCount()).isZero();
        }
    }
}
