package com.agentic.orchestrator.persistence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentic.orchestrator.OrchestratorApplication;
import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.NodeState;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunRepository;
import com.agentic.orchestrator.execution.RunScheduler;
import com.agentic.orchestrator.execution.RunStatus;
import com.agentic.orchestrator.governance.ApprovalRepository;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.governance.DecisionLog;
import com.agentic.orchestrator.plan.DecompositionPlanner;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves a run survives a restart by actually restarting: two Spring contexts, one after the other,
 * against the same database file.
 *
 * <p>Reloading repositories inside a single context would be cheaper and would prove less — it would
 * test the loader while leaving open whether anything was written in the first place. The whole
 * point of this increment is that a run parked on a human decision is not lost when the process
 * dies, and only a second process can demonstrate that.
 */
class RestartRecoveryTest {

    private static final String GREENFIELD =
            "Build a URL shortener service with shorten and redirect APIs";

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext boot(String databasePath) {
        // Passed as command-line arguments, not via .properties(). The builder's properties() feeds
        // the "default properties" source, which sits BELOW application.properties in precedence —
        // so src/test/resources would win and both contexts would quietly get their own throwaway
        // in-memory database, making this test pass for the wrong reason or fail for a confusing
        // one. Command-line arguments outrank both.
        return new SpringApplicationBuilder(OrchestratorApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        // No DB_CLOSE_DELAY: closing the context must release the file, exactly as
                        // a real shutdown would.
                        "--spring.datasource.url=jdbc:h2:file:" + databasePath,
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--orchestrator.simulation.node-delay-ms=0");
    }

    @Test
    @DisplayName("a run awaiting approval survives a process restart and can still be completed")
    void runSurvivesRestart() {
        String database = tempDir.resolve("orchestrator").toString();
        String runId;

        // ---------- first process: run until it parks on a human ----------
        try (ConfigurableApplicationContext first = boot(database)) {
            Plan plan = first.getBean(PlanRepository.class).save(
                    first.getBean(DecompositionPlanner.class).decompose(
                            first.getBean(RequirementNormalizer.class).normalize(GREENFIELD)));

            Run run = first.getBean(RunScheduler.class).start(plan, AutonomyLevel.L2_DELEGATED);
            runId = run.id();

            await().atMost(30, SECONDS)
                    .until(() -> run.status() == RunStatus.AWAITING_INPUT);

            assertThat(run.node("SCHEMA_LINK_CREATION").state())
                    .isEqualTo(NodeState.AWAITING_APPROVAL);
            assertThat(run.countInState(NodeState.SUCCEEDED)).isEqualTo(4);
        }

        // ---------- second process: everything is still there ----------
        try (ConfigurableApplicationContext second = boot(database)) {
            Run restored = second.getBean(RunRepository.class).findById(runId).orElseThrow();

            assertThat(restored.status()).isEqualTo(RunStatus.AWAITING_INPUT);
            assertThat(restored.autonomyLevel()).isEqualTo(AutonomyLevel.L2_DELEGATED);
            assertThat(restored.countInState(NodeState.SUCCEEDED)).isEqualTo(4);
            assertThat(restored.node("SCHEMA_LINK_CREATION").state())
                    .isEqualTo(NodeState.AWAITING_APPROVAL);

            // The plan came back too, or the run could not have been reconstructed at all.
            assertThat(restored.plan().tasks()).hasSize(13);

            // The audit trail is continuous across the restart rather than starting fresh.
            assertThat(second.getBean(EventLog.class).forRun(runId))
                    .isNotEmpty()
                    .extracting(event -> event.type().name())
                    .contains("RUN_CREATED", "APPROVAL_REQUESTED", "RUN_AWAITING_INPUT");

            // The pending decision is still pending, and still explains itself.
            ApprovalRepository approvals = second.getBean(ApprovalRepository.class);
            ApprovalRequest pending =
                    approvals.findPendingFor(runId, "SCHEMA_LINK_CREATION").orElseThrow();
            assertThat(pending.reasons()).isNotEmpty();
            assertThat(pending.policyIds()).contains("CHG-02");

            // ---------- and it can be driven to completion after the restart ----------
            RunScheduler scheduler = second.getBean(RunScheduler.class);
            for (int guard = 0; guard < 20 && !restored.status().isTerminal(); guard++) {
                await().atMost(30, SECONDS).until(() ->
                        restored.status().isTerminal()
                                || restored.status() == RunStatus.AWAITING_INPUT);

                List<ApprovalRequest> open = approvals.findByRun(runId).stream()
                        .filter(ApprovalRequest::isPending)
                        .toList();
                open.forEach(request ->
                        scheduler.approve(request.id(), "sravan", "approved after restart"));
            }

            assertThat(restored.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(restored.countInState(NodeState.SUCCEEDED)).isEqualTo(13);

            // Decisions taken in the second process are recorded against the same run.
            assertThat(second.getBean(DecisionLog.class).forRun(runId))
                    .anySatisfy(decision ->
                            assertThat(decision.actor()).isEqualTo("HUMAN:sravan"));
        }
    }

    @Test
    @DisplayName("a completed run is restored but not re-scheduled")
    void terminalRunsAreNotRestarted() {
        String database = tempDir.resolve("terminal").toString();
        String runId;

        try (ConfigurableApplicationContext first = boot(database)) {
            // Ambiguous plan at L3 still parks on CLARIFY, so stop it to reach a terminal state.
            Plan plan = first.getBean(PlanRepository.class).save(
                    first.getBean(DecompositionPlanner.class).decompose(
                            first.getBean(RequirementNormalizer.class)
                                    .normalize("Make the URL shortener more reliable and faster")));

            RunScheduler scheduler = first.getBean(RunScheduler.class);
            Run run = scheduler.start(plan, AutonomyLevel.L2_DELEGATED);
            runId = run.id();

            await().atMost(30, SECONDS).until(() -> run.status() == RunStatus.AWAITING_INPUT);
            scheduler.stop(runId, "abandoned");
            await().atMost(30, SECONDS).until(() -> run.status().isTerminal());
        }

        try (ConfigurableApplicationContext second = boot(database)) {
            Run restored = second.getBean(RunRepository.class).findById(runId).orElseThrow();

            assertThat(restored.status()).isEqualTo(RunStatus.STOPPED);
            assertThat(restored.stopReason()).isEqualTo("abandoned");
            assertThat(restored.node("CLARIFY").state()).isEqualTo(NodeState.CANCELLED);
        }
    }
}
