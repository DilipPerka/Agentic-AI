package com.agentic.orchestrator.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunRepository;
import com.agentic.orchestrator.execution.RunStatus;
import com.agentic.orchestrator.governance.ApprovalRepository;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.governance.DecisionLog;
import com.agentic.orchestrator.persistence.StateCleaner;
import com.agentic.orchestrator.plan.PlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "orchestrator.simulation.node-delay-ms=0")
class RunControllerTest {

    private static final String GREENFIELD =
            "Build a URL shortener service with shorten and redirect APIs";
    private static final String AMBIGUOUS =
            "Make the URL shortener more reliable and faster";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RunRepository runs;
    @Autowired private PlanRepository plans;
    @Autowired private EventLog events;
    @Autowired private ApprovalRepository approvals;
    @Autowired private DecisionLog decisions;
    @Autowired private StateCleaner storage;

    @BeforeEach
    void reset() {
        // Runs are asynchronous, so a previous test can still have one writing events. Wiping
        // storage underneath it trips the foreign keys and reports a failure in whichever test
        // happens to run next. Let anything in flight settle before clearing.
        runs.findAll().forEach(run -> await().atMost(30, SECONDS).until(() ->
                run.status().isTerminal() || run.status() == RunStatus.AWAITING_INPUT));

        runs.clear();
        plans.clear();
        events.clear();
        approvals.clear();
        decisions.clear();
        // The in-memory indexes and the database both have to go, or a later test sees rows a
        // previous one wrote and the failure looks like a bug in the code under test.
        storage.deleteEverything();
    }

    private String startRun(String requirement, AutonomyLevel autonomy) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartRunRequest(null, requirement, null, autonomy))))
                .andExpect(status().isAccepted())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private Run run(String runId) {
        return runs.findById(runId).orElseThrow();
    }

    private void awaitQuiescent(String runId) {
        await().atMost(30, SECONDS).until(() -> {
            RunStatus status = run(runId).status();
            return status.isTerminal() || status == RunStatus.AWAITING_INPUT;
        });
    }

    private List<ApprovalRequest> pendingFor(String runId) {
        return approvals.findByRun(runId).stream().filter(ApprovalRequest::isPending).toList();
    }

    /** Approves everything the run asks for, through the API, until it terminates. */
    private void approveThrough(String runId) throws Exception {
        for (int guard = 0; guard < 40 && !run(runId).status().isTerminal(); guard++) {
            awaitQuiescent(runId);
            for (ApprovalRequest request : pendingFor(runId)) {
                mockMvc.perform(post("/api/v1/approvals/{id}/approve", request.id())
                                .param("decidedBy", "tester")
                                .param("note", "reviewed"))
                        .andExpect(status().isOk());
            }
        }
    }

    // ------------------------------------------------------------------ execution

    @Test
    @DisplayName("a run pauses at the first action needing a human, and says why")
    void runPausesForApproval() throws Exception {
        String runId = startRun(GREENFIELD, AutonomyLevel.L2_DELEGATED);
        awaitQuiescent(runId);

        mockMvc.perform(get("/api/v1/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("AWAITING_INPUT"))
                .andExpect(jsonPath("$.autonomyLevel").value("L2_DELEGATED"))
                .andExpect(jsonPath("$.counts.awaitingApproval").value(1));

        assertThat(pendingFor(runId)).hasSize(1);
        assertThat(pendingFor(runId).get(0).taskId()).isEqualTo("SCHEMA_LINK_CREATION");
    }

    @Test
    @DisplayName("approving each request drives the run to completion")
    void approvingCompletesTheRun() throws Exception {
        String runId = startRun(GREENFIELD, AutonomyLevel.L2_DELEGATED);
        approveThrough(runId);

        mockMvc.perform(get("/api/v1/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.counts.total").value(13))
                .andExpect(jsonPath("$.counts.succeeded").value(13));

        // The three points a human had to touch, exactly as the plan predicted in increment 1.
        assertThat(approvals.findByRun(runId)).extracting(ApprovalRequest::taskId)
                .containsExactlyInAnyOrder(
                        "SCHEMA_LINK_CREATION", "IMPL_REDIRECT", "RELEASE_READINESS");
    }

    @Test
    @DisplayName("a task assigned to a human is never executed by an agent")
    void humanTaskRequiresAHuman() throws Exception {
        String runId = startRun(AMBIGUOUS, AutonomyLevel.L3_AUTONOMOUS);
        awaitQuiescent(runId);

        // Even at full autonomy: the CLARIFY task exists because a person is needed.
        assertThat(run(runId).status()).isEqualTo(RunStatus.AWAITING_INPUT);
        assertThat(pendingFor(runId)).extracting(ApprovalRequest::taskId).containsExactly("CLARIFY");
        assertThat(pendingFor(runId).get(0).policyIds()).contains("COM-01");
    }

    @Test
    @DisplayName("rejecting an approval fails the node and blocks what followed it")
    void rejectingBlocksDownstream() throws Exception {
        String runId = startRun(GREENFIELD, AutonomyLevel.L2_DELEGATED);
        awaitQuiescent(runId);

        String approvalId = pendingFor(runId).get(0).id();
        mockMvc.perform(post("/api/v1/approvals/{id}/reject", approvalId)
                        .param("decidedBy", "sravan")
                        .param("guidance", "use a surrogate key, not a natural one"))
                .andExpect(status().isOk());

        await().atMost(30, SECONDS).until(() -> run(runId).status().isTerminal());

        mockMvc.perform(get("/api/v1/runs/{id}", runId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.counts.failed").value(1));

        assertThat(run(runId).node("SCHEMA_LINK_CREATION").message())
                .contains("use a surrogate key");
        assertThat(run(runId).node("IMPL_LINK_CREATION").state().name()).isEqualTo("BLOCKED");
    }

    // ------------------------------------------------------------------ oversight surface

    @Test
    @DisplayName("the approval inbox states the impact, not just the task id")
    void approvalInboxExplainsItself() throws Exception {
        String runId = startRun(GREENFIELD, AutonomyLevel.L2_DELEGATED);
        awaitQuiescent(runId);

        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value("SCHEMA_LINK_CREATION"))
                .andExpect(jsonPath("$[0].blastRadius").value("HIGH"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].reasons").isNotEmpty())
                .andExpect(jsonPath("$[0].policyIds").value(org.hamcrest.Matchers.hasItem("CHG-02")));
    }

    @Test
    void decidingTwiceIsRejected() throws Exception {
        String runId = startRun(GREENFIELD, AutonomyLevel.L2_DELEGATED);
        awaitQuiescent(runId);
        String approvalId = pendingFor(runId).get(0).id();

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", approvalId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/approvals/{id}/approve", approvalId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.applied").value(false));
    }

    @Test
    void unknownApprovalIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/{id}/approve", "apr-missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("decisions are exposed with their actor and rationale")
    void exposesDecisions() throws Exception {
        String runId = startRun(GREENFIELD, AutonomyLevel.L2_DELEGATED);
        awaitQuiescent(runId);
        mockMvc.perform(post("/api/v1/approvals/{id}/approve", pendingFor(runId).get(0).id())
                        .param("decidedBy", "sravan").param("note", "schema reviewed"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/runs/{id}/decisions", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("HUMAN:sravan"))
                .andExpect(jsonPath("$[0].choice").value("APPROVED"))
                .andExpect(jsonPath("$[0].rationale").value("schema reviewed"));
    }

    @Test
    @DisplayName("the policy catalogue is inspectable")
    void exposesPolicies() throws Exception {
        mockMvc.perform(get("/api/v1/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].id").value("SEC-01"))
                .andExpect(jsonPath("$[0].effect").value("DENY"))
                .andExpect(jsonPath("$[0].rationale").isNotEmpty());
    }

    // ------------------------------------------------------------------ plumbing

    @Test
    void startsFromAnExistingPlan() throws Exception {
        MvcResult planResult = mockMvc.perform(post("/api/v1/decompose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecomposeRequest(
                                "Add click analytics and rate limiting to the existing service",
                                null))))
                .andExpect(status().isOk())
                .andReturn();

        String planId = objectMapper.readTree(planResult.getResponse().getContentAsString())
                .path("plan").path("id").asText();

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartRunRequest(planId, null, null, null))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.autonomyLevel").value("L2_DELEGATED"))
                .andExpect(jsonPath("$.scenarioType").value("BROWNFIELD"));
    }

    @Test
    void rejectsAStartWithNeitherPlanNorRequirement() throws Exception {
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartRunRequest(null, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnknownPlanId() throws Exception {
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartRunRequest("plan-missing", null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the event log records the governance transitions too")
    void eventLogIncludesGovernance() throws Exception {
        String runId = startRun(GREENFIELD, AutonomyLevel.L2_DELEGATED);
        awaitQuiescent(runId);

        mockMvc.perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seq").value(1));

        assertThat(events.forRun(runId)).extracting(event -> event.type().name())
                .contains("APPROVAL_REQUESTED", "NODE_AWAITING_APPROVAL", "RUN_AWAITING_INPUT");
    }

    @Test
    void eventsForAnUnknownRunAreNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/runs/{id}/events", "run-missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsRuns() throws Exception {
        startRun(AMBIGUOUS, AutonomyLevel.L2_DELEGATED);

        mockMvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
