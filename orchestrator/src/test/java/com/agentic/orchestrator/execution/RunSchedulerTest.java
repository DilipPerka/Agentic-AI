package com.agentic.orchestrator.execution;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import com.agentic.orchestrator.plan.Dependency;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import com.agentic.orchestrator.plan.Stage;
import com.agentic.orchestrator.plan.Task;
import com.agentic.orchestrator.plan.TaskGraph;
import com.agentic.orchestrator.reliability.CompensationResult;
import com.agentic.orchestrator.reliability.RetryPolicyResolver;
import com.agentic.orchestrator.replan.Fingerprinter;
import com.agentic.orchestrator.replan.PlanDiffer;
import com.agentic.orchestrator.replan.RequirementAmender;
import com.agentic.orchestrator.requirement.CapabilityCatalog;
import com.agentic.orchestrator.requirement.Requirement;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import com.agentic.orchestrator.requirement.ScenarioType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RunSchedulerTest {

    // StatePersistence.NONE: these tests are about scheduling and governance. A database in them
    // would add setup cost and a new way to fail without exercising anything they assert.
    private final RunRepository runs = new RunRepository(StatePersistence.NONE);
    private final EventLog events = new EventLog(StatePersistence.NONE);
    private final ApprovalRepository approvals = new ApprovalRepository(StatePersistence.NONE);
    private final DecisionLog decisions = new DecisionLog(StatePersistence.NONE);
    private final GateEvaluator gates = new GateEvaluator();
    private final GovernanceService governance =
            new GovernanceService(new ApprovalMatrix(), new PolicyEngine());

    // Retry disabled (maxAttempts 1) so these tests observe the scheduling and governance behaviour
    // directly rather than through a retry ladder. Retry has its own suite in ReliabilityTest.
    private final RetryPolicyResolver noRetries = new RetryPolicyResolver(1, 0);

    private RunScheduler scheduler(NodeExecutor executor, int maxConcurrency) {
        return new RunScheduler(runs, executor,
                context -> CompensationResult.undone("undone"),
                events, gates, governance, approvals, decisions, noRetries,
                new PlanRepository(StatePersistence.NONE), new DecompositionPlanner(),
                new RequirementAmender(new RequirementNormalizer(new CapabilityCatalog())),
                new PlanDiffer(), new Fingerprinter(),
                maxConcurrency, 30_000, false, 3, 4);
    }

    // ------------------------------------------------------------------ fixtures

    private static Task task(String id) {
        return task(id, BlastRadius.LOW, Set.of());
    }

    private static Task task(String id, BlastRadius radius, Set<String> writes) {
        return new Task(id, "Task " + id, id, Stage.IMPLEMENTATION, AgentRole.IMPLEMENTER,
                radius, List.of("done"), Set.of(), writes, false);
    }

    private static Plan planOf(List<Task> tasks, List<Dependency> dependencies) {
        TaskGraph graph = TaskGraph.of(tasks, dependencies);
        Requirement requirement = new Requirement("synthetic", "synthetic",
                ScenarioType.GREENFIELD, List.of(), List.of(), List.of());
        return new Plan("plan-test", requirement, tasks, graph.dependencies(),
                graph.executionLevels(), List.of(), Instant.now());
    }

    /** ROOT -> {A, B} -> JOIN: one fan-out, one barrier. All low-impact, so no approvals. */
    private static Plan diamond() {
        return planOf(
                List.of(task("ROOT"), task("A"), task("B"), task("JOIN")),
                List.of(Dependency.data("ROOT", "A", "x"),
                        Dependency.data("ROOT", "B", "x"),
                        Dependency.data("A", "JOIN", "x"),
                        Dependency.data("B", "JOIN", "x")));
    }

    private static void awaitTerminal(Run run) {
        await().atMost(15, SECONDS).until(() -> run.status().isTerminal());
    }

    private static void awaitQuiescent(Run run) {
        await().atMost(15, SECONDS).until(() ->
                run.status().isTerminal() || run.status() == RunStatus.AWAITING_INPUT);
    }

    private static NodeExecutor alwaysSucceeds() {
        return context -> NodeResult.success("ok", evidenceFor(context));
    }

    /** Produces the evidence the task declared, so exit gates pass. */
    private static Map<String, String> evidenceFor(ExecutionContext context) {
        return context.task().writes().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key -> "produced"));
    }

    private Run start(NodeExecutor executor, Plan plan) {
        return scheduler(executor, 4).start(plan, AutonomyLevel.L2_DELEGATED);
    }

    // ------------------------------------------------------------------ scheduling

    @Nested
    @DisplayName("scheduling")
    class Scheduling {

        @Test
        void completesEveryNode() {
            Run run = start(alwaysSucceeds(), diamond());
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.nodes()).allSatisfy(node ->
                    assertThat(node.state()).isEqualTo(NodeState.SUCCEEDED));
        }

        @Test
        @DisplayName("start() returns before the run finishes")
        void startIsAsynchronous() {
            CountDownLatch release = new CountDownLatch(1);
            NodeExecutor blocking = context -> {
                try {
                    release.await(10, SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return NodeResult.success("ok");
            };

            Run run = start(blocking, diamond());

            assertThat(run.status().isTerminal()).isFalse();
            release.countDown();
            awaitTerminal(run);
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        }

        @Test
        @DisplayName("independent branches genuinely run at the same time")
        void dispatchesSiblingsInParallel() {
            CountDownLatch aRunning = new CountDownLatch(1);
            CountDownLatch bRunning = new CountDownLatch(1);

            // Each sibling blocks until it observes the other running. Serial execution would time
            // both out, so a COMPLETED run is proof of real concurrency.
            NodeExecutor mutualWait = context -> {
                String id = context.task().id();
                try {
                    if (id.equals("A")) {
                        aRunning.countDown();
                        return bRunning.await(5, SECONDS)
                                ? NodeResult.success("A observed B running")
                                : NodeResult.failure("A never observed B");
                    }
                    if (id.equals("B")) {
                        bRunning.countDown();
                        return aRunning.await(5, SECONDS)
                                ? NodeResult.success("B observed A running")
                                : NodeResult.failure("B never observed A");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return NodeResult.failure("interrupted");
                }
                return NodeResult.success("ok");
            };

            Run run = start(mutualWait, diamond());
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        }

        @Test
        void joinWaitsForAllPredecessors() {
            Run run = start(alwaysSucceeds(), diamond());
            awaitTerminal(run);

            Instant joinStarted = run.node("JOIN").startedAt();
            assertThat(joinStarted).isAfterOrEqualTo(run.node("A").endedAt());
            assertThat(joinStarted).isAfterOrEqualTo(run.node("B").endedAt());
        }

        @Test
        void respectsMaxConcurrency() {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();

            NodeExecutor tracking = context -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(30);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                return NodeResult.success("ok");
            };

            Run run = scheduler(tracking, 1).start(diamond(), AutonomyLevel.L2_DELEGATED);
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(peak.get()).isEqualTo(1);
        }

        @Test
        void handlesTrivialPlan() {
            Run run = start(alwaysSucceeds(), planOf(List.of(task("ONLY")), List.of()));
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    // ------------------------------------------------------------------ failure

    @Nested
    @DisplayName("failure handling")
    class Failure {

        @Test
        @DisplayName("a failure blocks its dependents but not its siblings")
        void failurePropagatesOnlyDownstream() {
            NodeExecutor failsA = context -> context.task().id().equals("A")
                    ? NodeResult.failure("boom")
                    : NodeResult.success("ok");

            Run run = start(failsA, diamond());
            awaitTerminal(run);

            assertThat(run.node("A").state()).isEqualTo(NodeState.FAILED);
            assertThat(run.node("JOIN").state()).isEqualTo(NodeState.BLOCKED);
            assertThat(run.node("B").state()).isEqualTo(NodeState.SUCCEEDED);
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        void blockingCascades() {
            Plan chain = planOf(
                    List.of(task("ROOT"), task("MID"), task("LEAF")),
                    List.of(Dependency.data("ROOT", "MID", "x"),
                            Dependency.data("MID", "LEAF", "x")));

            NodeExecutor failsRoot = context -> context.task().id().equals("ROOT")
                    ? NodeResult.failure("boom")
                    : NodeResult.success("ok");

            Run run = start(failsRoot, chain);
            awaitTerminal(run);

            assertThat(run.node("MID").state()).isEqualTo(NodeState.BLOCKED);
            assertThat(run.node("LEAF").state()).isEqualTo(NodeState.BLOCKED);
        }

        @Test
        @DisplayName("an executor that throws fails its node instead of wedging the run")
        void executorExceptionsAreContained() {
            NodeExecutor throwsOnA = context -> {
                if (context.task().id().equals("A")) {
                    throw new IllegalStateException("executor blew up");
                }
                return NodeResult.success("ok");
            };

            Run run = start(throwsOnA, diamond());
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.node("A").message()).contains("executor blew up");
        }

        @Test
        @DisplayName("safe-stop cancels pending work and quiesces to STOPPED")
        void safeStopCancelsPendingNodes() {
            CountDownLatch rootRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            NodeExecutor holdRoot = context -> {
                if (context.task().id().equals("ROOT")) {
                    rootRunning.countDown();
                    try {
                        release.await(10, SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return NodeResult.success("ok");
            };

            RunScheduler runScheduler = scheduler(holdRoot, 4);
            Run run = runScheduler.start(diamond(), AutonomyLevel.L2_DELEGATED);

            await().atMost(10, SECONDS).until(() -> rootRunning.getCount() == 0);
            assertThat(runScheduler.stop(run.id(), "operator pulled the cord")).isTrue();

            release.countDown();
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.STOPPED);
            assertThat(run.node("ROOT").state()).isEqualTo(NodeState.SUCCEEDED);
            assertThat(run.node("A").state()).isEqualTo(NodeState.CANCELLED);
        }

        @Test
        void stoppingATerminalRunIsRejected() {
            RunScheduler runScheduler = scheduler(alwaysSucceeds(), 4);
            Run run = runScheduler.start(diamond(), AutonomyLevel.L2_DELEGATED);
            awaitTerminal(run);

            assertThat(runScheduler.stop(run.id(), "too late")).isFalse();
            assertThat(runScheduler.stop("run-does-not-exist", "nope")).isFalse();
        }
    }

    // ------------------------------------------------------------------ gates

    @Nested
    @DisplayName("exit gates")
    class Gates {

        @Test
        @DisplayName("a node that claims success without evidence is failed by the gate")
        void successWithoutEvidenceIsRefused() {
            Plan plan = planOf(
                    List.of(task("PRODUCES", BlastRadius.LOW, Set.of("SomeComponent"))),
                    List.of());

            // The executor says it worked. It produced nothing. The gate, not the executor, decides.
            NodeExecutor liar = context -> NodeResult.success("all done, honestly");

            Run run = start(liar, plan);
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.node("PRODUCES").state()).isEqualTo(NodeState.FAILED);
            assertThat(run.node("PRODUCES").message())
                    .contains("EvidenceProduced")
                    .contains("SomeComponent");
        }

        @Test
        @DisplayName("a gate refusal is recorded as a decision, not just a failure")
        void gateRefusalIsRecorded() {
            Plan plan = planOf(
                    List.of(task("PRODUCES", BlastRadius.LOW, Set.of("SomeComponent"))),
                    List.of());

            Run run = start(context -> NodeResult.success("claimed"), plan);
            awaitTerminal(run);

            assertThat(decisions.forRun(run.id()))
                    .anySatisfy(decision -> {
                        assertThat(decision.actor()).isEqualTo("GATE:ExitGate");
                        assertThat(decision.choice()).isEqualTo("NO");
                    });
        }

        @Test
        void evidenceSatisfiesTheGate() {
            Plan plan = planOf(
                    List.of(task("PRODUCES", BlastRadius.LOW, Set.of("SomeComponent"))),
                    List.of());

            Run run = start(alwaysSucceeds(), plan);
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    // ------------------------------------------------------------------ governance

    @Nested
    @DisplayName("governance")
    class Governance {

        /** ROOT -> {LOW_RISK, HIGH_RISK}. At L2, only the high-risk branch needs a human. */
        private Plan mixedRiskPlan() {
            return planOf(
                    List.of(task("ROOT"),
                            task("LOW_RISK", BlastRadius.LOW, Set.of()),
                            task("HIGH_RISK", BlastRadius.HIGH, Set.of())),
                    List.of(Dependency.data("ROOT", "LOW_RISK", "x"),
                            Dependency.data("ROOT", "HIGH_RISK", "x")));
        }

        @Test
        @DisplayName("a node needing approval waits without blocking its siblings")
        void approvalDoesNotBlockOtherBranches() {
            Run run = start(alwaysSucceeds(), mixedRiskPlan());
            awaitQuiescent(run);

            // The critical property: one branch is parked on a human while the other finished.
            assertThat(run.node("HIGH_RISK").state()).isEqualTo(NodeState.AWAITING_APPROVAL);
            assertThat(run.node("LOW_RISK").state()).isEqualTo(NodeState.SUCCEEDED);
            assertThat(run.status()).isEqualTo(RunStatus.AWAITING_INPUT);
            assertThat(run.status().isTerminal()).isFalse();
        }

        @Test
        @DisplayName("the approval request explains itself")
        void approvalRequestCarriesItsReasons() {
            Run run = start(alwaysSucceeds(), mixedRiskPlan());
            awaitQuiescent(run);

            ApprovalRequest request = approvals.findPendingFor(run.id(), "HIGH_RISK").orElseThrow();
            assertThat(request.blastRadius()).isEqualTo(BlastRadius.HIGH);
            assertThat(request.reasons()).isNotEmpty();
            assertThat(String.join(" ", request.reasons())).contains("L2_DELEGATED");
        }

        @Test
        @DisplayName("repeated ticks do not raise duplicate approval requests")
        void approvalRequestsAreNotDuplicated() {
            Run run = start(alwaysSucceeds(), mixedRiskPlan());
            awaitQuiescent(run);

            assertThat(approvals.findByRun(run.id()))
                    .filteredOn(request -> request.taskId().equals("HIGH_RISK"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("approving resumes the run")
        void approvalResumesTheRun() {
            RunScheduler runScheduler = scheduler(alwaysSucceeds(), 4);
            Run run = runScheduler.start(mixedRiskPlan(), AutonomyLevel.L2_DELEGATED);
            awaitQuiescent(run);

            String approvalId = approvals.findPendingFor(run.id(), "HIGH_RISK").orElseThrow().id();
            assertThat(runScheduler.approve(approvalId, "sravan", "reviewed the diff")).isTrue();

            awaitTerminal(run);
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.node("HIGH_RISK").state()).isEqualTo(NodeState.SUCCEEDED);
        }

        @Test
        @DisplayName("rejecting fails the node and blocks what depended on it")
        void rejectionFailsTheNode() {
            Plan plan = planOf(
                    List.of(task("ROOT"),
                            task("HIGH_RISK", BlastRadius.HIGH, Set.of()),
                            task("DEPENDENT")),
                    List.of(Dependency.data("ROOT", "HIGH_RISK", "x"),
                            Dependency.data("HIGH_RISK", "DEPENDENT", "x")));

            RunScheduler runScheduler = scheduler(alwaysSucceeds(), 4);
            Run run = runScheduler.start(plan, AutonomyLevel.L2_DELEGATED);
            awaitQuiescent(run);

            String approvalId = approvals.findPendingFor(run.id(), "HIGH_RISK").orElseThrow().id();
            assertThat(runScheduler.reject(approvalId, "sravan", "wrong approach")).isTrue();

            awaitTerminal(run);
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.node("HIGH_RISK").state()).isEqualTo(NodeState.FAILED);
            assertThat(run.node("HIGH_RISK").message()).contains("wrong approach");
            assertThat(run.node("DEPENDENT").state()).isEqualTo(NodeState.BLOCKED);
        }

        @Test
        @DisplayName("a decision is recorded with the human as actor")
        void humanDecisionsAreAttributed() {
            RunScheduler runScheduler = scheduler(alwaysSucceeds(), 4);
            Run run = runScheduler.start(mixedRiskPlan(), AutonomyLevel.L2_DELEGATED);
            awaitQuiescent(run);

            String approvalId = approvals.findPendingFor(run.id(), "HIGH_RISK").orElseThrow().id();
            runScheduler.approve(approvalId, "sravan", "looks right");
            awaitTerminal(run);

            assertThat(decisions.forRun(run.id()))
                    .anySatisfy(decision -> {
                        assertThat(decision.actor()).isEqualTo("HUMAN:sravan");
                        assertThat(decision.choice()).isEqualTo("APPROVED");
                        assertThat(decision.rationale()).isEqualTo("looks right");
                    });
        }

        @Test
        @DisplayName("L3 removes the matrix approval but not the policy one")
        void autonomyRelaxesTheMatrixOnly() {
            // HIGH blast radius is AUTO at L3, so this branch proceeds unattended...
            Run high = scheduler(alwaysSucceeds(), 4)
                    .start(mixedRiskPlan(), AutonomyLevel.L3_AUTONOMOUS);
            awaitTerminal(high);
            assertThat(high.status()).isEqualTo(RunStatus.COMPLETED);

            // ...but a migration still requires a human, at any autonomy level.
            Plan migration = planOf(
                    List.of(task("SCHEMA", BlastRadius.LOW, Set.of("migration:THING"))), List.of());
            Run schema = scheduler(alwaysSucceeds(), 4)
                    .start(migration, AutonomyLevel.L3_AUTONOMOUS);
            awaitQuiescent(schema);

            assertThat(schema.node("SCHEMA").state()).isEqualTo(NodeState.AWAITING_APPROVAL);
        }

        @Test
        @DisplayName("a policy DENY blocks the node at any autonomy level")
        void policyDenyIsAbsolute() {
            Plan plan = planOf(
                    List.of(task("WRITES_SECRET", BlastRadius.LOW, Set.of("app.secret.properties"))),
                    List.of());

            Run run = scheduler(alwaysSucceeds(), 4).start(plan, AutonomyLevel.L3_AUTONOMOUS);
            awaitTerminal(run);

            assertThat(run.node("WRITES_SECRET").state()).isEqualTo(NodeState.BLOCKED);
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(events.forRun(run.id()))
                    .anySatisfy(event ->
                            assertThat(event.type()).isEqualTo(EventType.POLICY_DENIED));
        }

        @Test
        @DisplayName("safe-stop cancels an outstanding approval too")
        void safeStopCancelsAwaitingNodes() {
            RunScheduler runScheduler = scheduler(alwaysSucceeds(), 4);
            Run run = runScheduler.start(mixedRiskPlan(), AutonomyLevel.L2_DELEGATED);
            awaitQuiescent(run);

            assertThat(runScheduler.stop(run.id(), "abandoning this run")).isTrue();
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.STOPPED);
            assertThat(run.node("HIGH_RISK").state()).isEqualTo(NodeState.CANCELLED);
        }

        @Test
        void decidingATerminalRunIsRejected() {
            RunScheduler runScheduler = scheduler(alwaysSucceeds(), 4);
            Run run = runScheduler.start(mixedRiskPlan(), AutonomyLevel.L2_DELEGATED);
            awaitQuiescent(run);

            String approvalId = approvals.findPendingFor(run.id(), "HIGH_RISK").orElseThrow().id();
            assertThat(runScheduler.approve(approvalId, "sravan", "yes")).isTrue();
            // Second decision on the same request must not apply.
            assertThat(runScheduler.approve(approvalId, "someone-else", "yes again")).isFalse();
            assertThat(runScheduler.approve("apr-nope", "sravan", "no such request")).isFalse();
        }
    }

    // ------------------------------------------------------------------ event log

    @Nested
    @DisplayName("event log")
    class Events {

        @Test
        void isOrderedAndMonotonic() {
            Run run = start(alwaysSucceeds(), diamond());
            awaitTerminal(run);

            List<RunEvent> log = events.forRun(run.id());

            assertThat(log).extracting(RunEvent::seq).isSorted();
            assertThat(log.get(0).seq()).isEqualTo(1);
            assertThat(log).extracting(RunEvent::type)
                    .startsWith(EventType.RUN_CREATED, EventType.RUN_STARTED)
                    .endsWith(EventType.RUN_COMPLETED);
            assertThat(log).filteredOn(event -> event.type() == EventType.NODE_STARTED).hasSize(4);
        }

        @Test
        void supportsIncrementalReads() {
            Run run = start(alwaysSucceeds(), diamond());
            awaitTerminal(run);

            List<RunEvent> all = events.forRun(run.id());
            long midpoint = all.get(all.size() / 2).seq();
            List<RunEvent> tail = events.forRunSince(run.id(), midpoint);

            assertThat(tail).hasSize(all.size() - (int) midpoint);
            assertThat(tail).allSatisfy(event -> assertThat(event.seq()).isGreaterThan(midpoint));
        }

        @Test
        @DisplayName("a node only sees the outputs of its declared predecessors")
        void upstreamOutputsAreScopedToPredecessors() {
            Set<String> keysSeenByJoin = java.util.concurrent.ConcurrentHashMap.newKeySet();

            NodeExecutor recording = context -> {
                if (context.task().id().equals("JOIN")) {
                    keysSeenByJoin.addAll(context.upstreamOutputs().keySet());
                }
                return NodeResult.success("ok", Map.of("marker", context.task().id()));
            };

            Run run = start(recording, diamond());
            awaitTerminal(run);

            assertThat(keysSeenByJoin).containsExactlyInAnyOrder("A.marker", "B.marker");
            assertThat(keysSeenByJoin).noneMatch(key -> key.startsWith("ROOT."));
        }
    }
}
