package com.agentic.orchestrator.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.EventType;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunScheduler;
import com.agentic.orchestrator.execution.RunStatus;
import com.agentic.orchestrator.governance.ApprovalRepository;
import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.plan.DecompositionPlanner;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Every assertion is scoped to a run this test started.
 *
 * <p>Deliberately no shared-state cleanup between tests. Clearing the run repository while an
 * asynchronous run is still executing leaves the scheduler holding a run nobody can look up, which
 * produces failures in whichever test happens to run next rather than in the one at fault. Scoping
 * to {@code forRun} removes the need for cleanup entirely.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "orchestrator.simulation.node-delay-ms=10",
        "spring.datasource.url=jdbc:h2:mem:metrics;DB_CLOSE_DELAY=-1"
})
class MetricsServiceTest {

    @Autowired MetricsService metrics;
    @Autowired RunScheduler scheduler;
    @Autowired EventLog events;
    @Autowired ApprovalRepository approvals;
    @Autowired DecompositionPlanner planner;
    @Autowired PlanRepository plans;
    @Autowired RequirementNormalizer normalizer;

    private static final String GREENFIELD =
            "Build a URL shortener service with shorten and redirect APIs";

    /**
     * Saved, not just built: {@code run.plan_id} is a foreign key, so a run started from an
     * unsaved plan fails on its first write-through rather than on anything to do with metrics.
     */
    private Plan plan(String requirement) {
        return plans.save(planner.decompose(normalizer.normalize(requirement, null)));
    }

    private Run started(String requirement, AutonomyLevel level) {
        Run run = scheduler.start(plan(requirement), level);
        await().atMost(Duration.ofSeconds(20)).until(() ->
                run.status().isTerminal() || run.status() == RunStatus.AWAITING_INPUT);
        return run;
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("empty state")
    class Empty {

        @Test
        @DisplayName("rates are null rather than zero when there is nothing to divide")
        void reportsNoDataRatherThanZero() {
            MetricsSnapshot snapshot = metrics.forRun("run-does-not-exist");

            // 0% reads as "everything failed". A dashboard must be able to say "nothing has happened
            // yet" without looking like an incident.
            assertThat(snapshot.runs().total()).isZero();
            assertThat(snapshot.runs().successRatePct()).isNull();
            assertThat(snapshot.nodes().successRatePct()).isNull();
            assertThat(snapshot.latency().meanRunMs()).isNull();
            assertThat(snapshot.reliability().meanTimeToRecoveryMs()).isNull();
        }
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        void countsTheNodesOfARun() {
            Run run = started(GREENFIELD, AutonomyLevel.L3_AUTONOMOUS);

            MetricsSnapshot snapshot = metrics.forRun(run.id());

            assertThat(snapshot.runs().total()).isEqualTo(1);
            assertThat(snapshot.nodes().attempted()).isPositive();
            assertThat(snapshot.nodes().succeeded()).isPositive();
            assertThat(snapshot.latency().meanNodeMs()).isNotNull();
            assertThat(snapshot.stages()).isNotEmpty();
        }

        @Test
        @DisplayName("a run parked on a human has no success rate, rather than a rate of zero")
        void inFlightRunsAreExcludedFromTheRate() {
            Run run = scheduler.start(plan(GREENFIELD), AutonomyLevel.L2_DELEGATED);
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    run.status() == RunStatus.AWAITING_INPUT);

            MetricsSnapshot snapshot = metrics.forRun(run.id());

            assertThat(snapshot.runs().active()).isEqualTo(1);
            assertThat(snapshot.runs().successRatePct()).isNull();
            assertThat(snapshot.governance().stillPending()).isEqualTo(1);
        }

        @Test
        @DisplayName("blocked nodes are counted apart from failures")
        void blockedNodesAreNotFailures() {
            Run run = scheduler.start(plan(GREENFIELD), AutonomyLevel.L2_DELEGATED);
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    run.status() == RunStatus.AWAITING_INPUT);

            String approvalId = approvals.findByRun(run.id()).get(0).id();
            scheduler.reject(approvalId, "tester", "Not this way");
            await().atMost(Duration.ofSeconds(20)).until(() -> run.status().isTerminal());

            MetricsSnapshot snapshot = metrics.forRun(run.id());

            // One node was refused; the rest never ran. Counting those as failures would turn a
            // single refused decision into an apparent collapse.
            assertThat(snapshot.nodes().failed()).isEqualTo(1);
            assertThat(snapshot.nodes().blocked()).isPositive();
            assertThat(snapshot.governance().rejected()).isEqualTo(1);
            assertThat(snapshot.governance().meanWaitMs()).isNotNull();
        }
    }

    @Nested
    @DisplayName("scoping")
    class Scoping {

        @Test
        void perRunMetricsSeeOnlyThatRun() {
            Run first = started(GREENFIELD, AutonomyLevel.L3_AUTONOMOUS);
            Run second = started("Add click analytics to the existing service",
                    AutonomyLevel.L3_AUTONOMOUS);

            assertThat(metrics.forRun(first.id()).runs().total()).isEqualTo(1);
            assertThat(metrics.forRun(second.id()).runs().total()).isEqualTo(1);
            // Both runs are visible fleet-wide, whatever else the suite has left behind.
            assertThat(metrics.overall().runs().total()).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("recovery timing")
    class Recovery {

        @Test
        @DisplayName("MTTR measures first failure to eventual success, and excludes what never recovered")
        void computesMeanTimeToRecovery() throws InterruptedException {
            // A real run, because run_event carries a foreign key to it — synthetic ids cannot be
            // persisted, and an event that fails to store is not in the log the metrics read.
            Run run = started(GREENFIELD, AutonomyLevel.L3_AUTONOMOUS);
            String runId = run.id();

            events.append(runId, EventType.RETRY_SCHEDULED, "SYNTH_RECOVERS", "attempt 1 failed");
            Thread.sleep(40);
            events.append(runId, EventType.NODE_SUCCEEDED, "SYNTH_RECOVERS", "attempt 2 worked");
            events.append(runId, EventType.NODE_FAILED, "SYNTH_STUCK", "gave up");

            MetricsSnapshot snapshot = metrics.forRun(runId);

            // The unrecovered node must not be averaged in as zero, which would flatter the number,
            // nor as infinity, which would destroy it. Two numbers is the honest answer.
            assertThat(snapshot.reliability().recoveredFailures()).isEqualTo(1);
            assertThat(snapshot.reliability().unrecoveredFailures()).isEqualTo(1);
            assertThat(snapshot.reliability().meanTimeToRecoveryMs()).isGreaterThanOrEqualTo(30L);
        }
    }
}
