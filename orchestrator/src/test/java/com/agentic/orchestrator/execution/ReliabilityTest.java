package com.agentic.orchestrator.execution;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentic.orchestrator.governance.ApprovalMatrix;
import com.agentic.orchestrator.governance.ApprovalRepository;
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
import com.agentic.orchestrator.reliability.CompensationExecutor;
import com.agentic.orchestrator.reliability.CompensationResult;
import com.agentic.orchestrator.reliability.RetryPolicy;
import com.agentic.orchestrator.reliability.RetryPolicyResolver;
import com.agentic.orchestrator.replan.Fingerprinter;
import com.agentic.orchestrator.replan.PlanDiffer;
import com.agentic.orchestrator.replan.RequirementAmender;
import com.agentic.orchestrator.requirement.CapabilityCatalog;
import com.agentic.orchestrator.requirement.Requirement;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import com.agentic.orchestrator.requirement.ScenarioType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Failure-injection tests for CR4.7: bounded retry, fallback, rollback and safe-stop. */
class ReliabilityTest {

    private final RunRepository runs = new RunRepository(StatePersistence.NONE);
    private final EventLog events = new EventLog(StatePersistence.NONE);
    private final ApprovalRepository approvals = new ApprovalRepository(StatePersistence.NONE);
    private final DecisionLog decisions = new DecisionLog(StatePersistence.NONE);
    private final GateEvaluator gates = new GateEvaluator();
    private final GovernanceService governance =
            new GovernanceService(new ApprovalMatrix(), new PolicyEngine());

    /** Three attempts, 10ms backoff — fast enough for tests, still genuinely delayed. */
    private final RetryPolicyResolver retries = new RetryPolicyResolver(3, 10);

    private static final CompensationExecutor UNDOES_EVERYTHING =
            context -> CompensationResult.undone("undid " + context.task().id());

    private RunScheduler scheduler(NodeExecutor executor) {
        return scheduler(executor, UNDOES_EVERYTHING, 30_000);
    }

    private RunScheduler scheduler(NodeExecutor executor, CompensationExecutor compensator,
                                   long timeoutMs) {
        return new RunScheduler(runs, executor, compensator, events, gates, governance, approvals,
                decisions, retries,
                new PlanRepository(StatePersistence.NONE), new DecompositionPlanner(),
                new RequirementAmender(new RequirementNormalizer(new CapabilityCatalog())),
                new PlanDiffer(), new Fingerprinter(),
                4, timeoutMs, true, 3, 4);
    }

    // ------------------------------------------------------------------ fixtures

    private static Task task(String id) {
        return task(id, BlastRadius.LOW, Set.of(id + ":out"));
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

    private static Plan chain() {
        return planOf(
                List.of(task("FIRST"), task("SECOND"), task("THIRD")),
                List.of(Dependency.data("FIRST", "SECOND", "x"),
                        Dependency.data("SECOND", "THIRD", "x")));
    }

    private static Map<String, String> evidence(ExecutionContext context) {
        return context.task().writes().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key -> "produced"));
    }

    private static NodeExecutor succeeds() {
        return context -> NodeResult.success("ok", evidence(context));
    }

    private static void awaitTerminal(Run run) {
        await().atMost(20, SECONDS).until(() -> run.status().isTerminal());
    }

    private Run start(RunScheduler scheduler, Plan plan) {
        return scheduler.start(plan, AutonomyLevel.L3_AUTONOMOUS);
    }

    // ------------------------------------------------------------------ retry

    @Nested
    @DisplayName("bounded retry")
    class Retry {

        @Test
        @DisplayName("a transient failure is retried and can succeed on a later attempt")
        void retriesUntilSuccess() {
            AtomicInteger attempts = new AtomicInteger();
            NodeExecutor flaky = context -> {
                if (context.task().id().equals("SECOND") && attempts.incrementAndGet() < 3) {
                    return NodeResult.failure("transient blip " + attempts.get());
                }
                return NodeResult.success("ok", evidence(context));
            };

            Run run = start(scheduler(flaky), chain());
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.node("SECOND").attempt()).isEqualTo(3);
            assertThat(events.forRun(run.id()))
                    .filteredOn(event -> event.type() == EventType.RETRY_SCHEDULED)
                    .hasSize(2);
        }

        @Test
        @DisplayName("retries are bounded; the node does not loop forever")
        void retriesAreBounded() {
            AtomicInteger attempts = new AtomicInteger();
            NodeExecutor alwaysFails = context -> {
                if (context.task().id().equals("SECOND")) {
                    attempts.incrementAndGet();
                    return NodeResult.failure("still broken");
                }
                return NodeResult.success("ok", evidence(context));
            };

            Run run = start(scheduler(alwaysFails), chain());
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            // 3 attempts by policy, plus exactly one degraded fallback attempt. Never more.
            assertThat(attempts.get()).isEqualTo(4);
            assertThat(run.node("THIRD").state()).isEqualTo(NodeState.BLOCKED);
        }

        @Test
        @DisplayName("a permanent failure skips the retry ladder entirely")
        void permanentFailuresAreNotRetried() {
            AtomicInteger attempts = new AtomicInteger();
            NodeExecutor permanent = context -> {
                if (context.task().id().equals("SECOND")) {
                    attempts.incrementAndGet();
                    return NodeResult.permanentFailure("configuration is wrong");
                }
                return NodeResult.success("ok", evidence(context));
            };

            Run run = start(scheduler(permanent), chain());
            awaitTerminal(run);

            assertThat(attempts.get()).isEqualTo(1);
            assertThat(events.forRun(run.id()))
                    .noneMatch(event -> event.type() == EventType.RETRY_SCHEDULED);
        }

        @Test
        @DisplayName("high blast radius work is not retried automatically")
        void riskyWorkFailsToAHumanInsteadOfRetrying() {
            Plan risky = planOf(
                    List.of(task("RISKY", BlastRadius.HIGH, Set.of("RISKY:out"))), List.of());

            AtomicInteger attempts = new AtomicInteger();
            NodeExecutor fails = context -> {
                attempts.incrementAndGet();
                return NodeResult.failure("boom");
            };

            Run run = start(scheduler(fails), risky);
            awaitTerminal(run);

            // Repeating a migration or a hot-path change automatically doubles the chance of damage
            // and removes the human who should be looking at it.
            assertThat(attempts.get()).isEqualTo(1);
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        void backoffGrowsAndIsCapped() {
            RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), 2.0,
                    Duration.ofMillis(500));

            assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofMillis(100));
            assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofMillis(200));
            assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofMillis(400));
            assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofMillis(500)); // capped
            assertThat(policy.backoffAfter(9)).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("a node waiting on a backoff holds no thread and blocks no sibling")
        void backoffDoesNotBlockOtherBranches() {
            Plan parallel = planOf(
                    List.of(task("ROOT"), task("FLAKY"), task("HEALTHY")),
                    List.of(Dependency.data("ROOT", "FLAKY", "x"),
                            Dependency.data("ROOT", "HEALTHY", "x")));

            AtomicInteger attempts = new AtomicInteger();
            NodeExecutor flaky = context -> {
                if (context.task().id().equals("FLAKY") && attempts.incrementAndGet() < 3) {
                    return NodeResult.failure("blip");
                }
                return NodeResult.success("ok", evidence(context));
            };

            Run run = start(scheduler(flaky), parallel);
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            // HEALTHY finished long before FLAKY exhausted its backoffs.
            assertThat(run.node("HEALTHY").endedAt()).isBefore(run.node("FLAKY").endedAt());
        }
    }

    // ------------------------------------------------------------------ fallback

    @Nested
    @DisplayName("fallback")
    class Fallback {

        @Test
        @DisplayName("after retries are exhausted, one degraded attempt is made")
        void degradedFallbackRunsOnce() {
            List<Boolean> degradedFlags = new CopyOnWriteArrayList<>();
            NodeExecutor onlyWorksDegraded = context -> {
                if (!context.task().id().equals("SECOND")) {
                    return NodeResult.success("ok", evidence(context));
                }
                degradedFlags.add(context.degraded());
                return context.degraded()
                        ? NodeResult.success("reduced output", evidence(context))
                        : NodeResult.failure("cannot produce the full implementation");
            };

            Run run = start(scheduler(onlyWorksDegraded), chain());
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            // Three normal attempts, then exactly one degraded one.
            assertThat(degradedFlags).containsExactly(false, false, false, true);
            assertThat(run.node("SECOND").completedDegraded()).isTrue();
        }

        @Test
        @DisplayName("a degraded success is flagged rather than passed off as a normal one")
        void degradedSuccessIsRecorded() {
            NodeExecutor onlyWorksDegraded = context -> {
                if (!context.task().id().equals("SECOND")) {
                    return NodeResult.success("ok", evidence(context));
                }
                return context.degraded()
                        ? NodeResult.success("reduced output", evidence(context))
                        : NodeResult.failure("nope");
            };

            Run run = start(scheduler(onlyWorksDegraded), chain());
            awaitTerminal(run);

            assertThat(events.forRun(run.id()))
                    .anyMatch(event -> event.type() == EventType.NODE_DEGRADED);
            assertThat(decisions.forRun(run.id()))
                    .anySatisfy(decision -> assertThat(decision.question())
                            .contains("degraded mode"));
            // Other nodes are unaffected: only the node that needed the fallback is marked.
            assertThat(run.node("FIRST").completedDegraded()).isFalse();
        }
    }

    // ------------------------------------------------------------------ timeout

    @Nested
    @DisplayName("timeout")
    class Timeout {

        @Test
        @DisplayName("a hung executor fails its node instead of hanging the run")
        void hungExecutorTimesOut() {
            CountDownLatch neverReleased = new CountDownLatch(1);
            NodeExecutor hangs = context -> {
                if (context.task().id().equals("SECOND")) {
                    try {
                        neverReleased.await(30, SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return NodeResult.success("finally", evidence(context));
                }
                return NodeResult.success("ok", evidence(context));
            };

            Run run = start(scheduler(hangs, UNDOES_EVERYTHING, 100), chain());
            awaitTerminal(run);

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(events.forRun(run.id()))
                    .anyMatch(event -> event.type() == EventType.NODE_TIMED_OUT);
            assertThat(run.node("THIRD").state()).isEqualTo(NodeState.BLOCKED);
        }
    }

    // ------------------------------------------------------------------ rollback

    @Nested
    @DisplayName("compensating rollback")
    class Rollback {

        @Test
        @DisplayName("succeeded nodes are undone in reverse order")
        void undoesInReverseOrder() {
            List<String> undone = new CopyOnWriteArrayList<>();
            CompensationExecutor recording = context -> {
                undone.add(context.task().id());
                return CompensationResult.undone("undid " + context.task().id());
            };

            RunScheduler runScheduler = scheduler(succeeds(), recording, 30_000);
            Run run = start(runScheduler, chain());
            awaitTerminal(run);
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);

            assertThat(runScheduler.rollback(run.id(), "changed our minds")).isTrue();

            // Most recent work first: undoing FIRST before THIRD would remove something THIRD
            // still depends on.
            assertThat(undone).containsExactly("THIRD", "SECOND", "FIRST");
            assertThat(run.status()).isEqualTo(RunStatus.ROLLED_BACK);
            assertThat(run.nodes()).allSatisfy(node ->
                    assertThat(node.state()).isEqualTo(NodeState.ROLLED_BACK));
        }

        @Test
        @DisplayName("a failed compensation halts the rollback and escalates")
        void partialRollbackEscalates() {
            List<String> attempted = new CopyOnWriteArrayList<>();
            CompensationExecutor failsOnSecond = context -> {
                attempted.add(context.task().id());
                return context.task().id().equals("SECOND")
                        ? CompensationResult.failed("could not revert SECOND")
                        : CompensationResult.undone("undid " + context.task().id());
            };

            RunScheduler runScheduler = scheduler(succeeds(), failsOnSecond, 30_000);
            Run run = start(runScheduler, chain());
            awaitTerminal(run);

            assertThat(runScheduler.rollback(run.id(), "trying to undo")).isFalse();

            // Stopped at the failure rather than carrying on into FIRST. Compounding a known-partial
            // state with further changes is exactly what should not happen automatically.
            assertThat(attempted).containsExactly("THIRD", "SECOND");
            assertThat(run.node("THIRD").state()).isEqualTo(NodeState.ROLLED_BACK);
            assertThat(run.node("SECOND").state()).isEqualTo(NodeState.BLOCKED);
            assertThat(run.node("FIRST").state()).isEqualTo(NodeState.SUCCEEDED);
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);

            assertThat(decisions.forRun(run.id()))
                    .anySatisfy(decision -> assertThat(decision.question())
                            .contains("Can the rollback continue"));
        }

        @Test
        @DisplayName("a compensator that throws is treated as a failed compensation")
        void throwingCompensatorEscalates() {
            CompensationExecutor throwing = context -> {
                throw new IllegalStateException("git is in a detached state");
            };

            RunScheduler runScheduler = scheduler(succeeds(), throwing, 30_000);
            Run run = start(runScheduler, chain());
            awaitTerminal(run);

            assertThat(runScheduler.rollback(run.id(), "attempting undo")).isFalse();
            assertThat(events.forRun(run.id()))
                    .anyMatch(event -> event.type() == EventType.COMPENSATION_FAILED);
        }

        @Test
        @DisplayName("an active run cannot be rolled back underneath itself")
        void rollbackRequiresASettledRun() {
            CountDownLatch hold = new CountDownLatch(1);
            NodeExecutor slow = context -> {
                try {
                    hold.await(20, SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return NodeResult.success("ok", evidence(context));
            };

            RunScheduler runScheduler = scheduler(slow);
            Run run = start(runScheduler, chain());

            assertThat(runScheduler.rollback(run.id(), "too early")).isFalse();
            hold.countDown();
            awaitTerminal(run);
        }

        @Test
        void rollbackOfAnUnknownRunIsRejected() {
            assertThat(scheduler(succeeds()).rollback("run-missing", "nope")).isFalse();
        }
    }

    // ------------------------------------------------------------------ pause

    @Nested
    @DisplayName("pause and resume")
    class Pause {

        @Test
        @DisplayName("pausing stops dispatch; resuming continues where it left off")
        void pauseHoldsAndResumeContinues() {
            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            NodeExecutor holdFirst = context -> {
                if (context.task().id().equals("FIRST")) {
                    firstRunning.countDown();
                    try {
                        release.await(20, SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return NodeResult.success("ok", evidence(context));
            };

            RunScheduler runScheduler = scheduler(holdFirst);
            Run run = start(runScheduler, chain());

            await().atMost(20, SECONDS).until(() -> firstRunning.getCount() == 0);
            assertThat(runScheduler.pause(run.id(), "operator holding")).isTrue();

            release.countDown();

            // FIRST finishes, then nothing else dispatches: the run parks at PAUSED, not COMPLETED.
            await().atMost(20, SECONDS).until(() ->
                    run.node("FIRST").state() == NodeState.SUCCEEDED);
            await().atMost(5, SECONDS).until(() -> run.countInState(NodeState.RUNNING) == 0);

            assertThat(run.status()).isEqualTo(RunStatus.PAUSED);
            assertThat(run.node("SECOND").state()).isEqualTo(NodeState.PENDING);

            assertThat(runScheduler.resume(run.id())).isTrue();
            awaitTerminal(run);
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        }

        @Test
        void pausingTwiceIsRejected() {
            RunScheduler runScheduler = scheduler(succeeds());
            Run run = start(runScheduler, chain());
            awaitTerminal(run);

            assertThat(runScheduler.pause(run.id(), "too late")).isFalse();
            assertThat(runScheduler.resume(run.id())).isFalse();
            assertThat(runScheduler.pause("run-missing", "nope")).isFalse();
        }

        @Test
        @DisplayName("a paused run can be rolled back")
        void pausedRunsCanBeRolledBack() {
            RunScheduler runScheduler = scheduler(succeeds());
            Plan single = planOf(List.of(task("ONLY")), List.of());
            Run run = start(runScheduler, single);
            awaitTerminal(run);

            assertThat(runScheduler.rollback(run.id(), "undo it")).isTrue();
            assertThat(run.node("ONLY").state()).isEqualTo(NodeState.ROLLED_BACK);
        }
    }
}
