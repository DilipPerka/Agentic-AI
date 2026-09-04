package com.agentic.orchestrator.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.orchestrator.persistence.StateCleaner;
import com.agentic.orchestrator.plan.PlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class DecompositionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlanRepository plans;

    @Autowired
    private StateCleaner storage;

    @BeforeEach
    void reset() {
        plans.clear();
        storage.deleteEverything();
    }

    private String body(String requirement) throws Exception {
        return objectMapper.writeValueAsString(new DecomposeRequest(requirement, null));
    }

    @Test
    void contextLoads() {
        // Fails fast if the bean graph is wrong, before any behaviour test muddies the diagnosis.
    }

    @Test
    @DisplayName("POST /decompose returns a plan with a summary")
    void decomposesARequirement() throws Exception {
        mockMvc.perform(post("/api/v1/decompose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Build a URL shortener service with shorten and redirect APIs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.scenarioType").value("GREENFIELD"))
                .andExpect(jsonPath("$.summary.taskCount", greaterThan(5)))
                .andExpect(jsonPath("$.summary.maxParallelism", greaterThan(1)))
                .andExpect(jsonPath("$.summary.humanApprovalTasks").isNotEmpty())
                .andExpect(jsonPath("$.plan.executionLevels").isArray())
                .andExpect(jsonPath("$.plan.planningNotes").isNotEmpty());
    }

    @Test
    @DisplayName("an ambiguous requirement returns a two-task plan, not an error")
    void ambiguousRequirementStillReturnsAPlan() throws Exception {
        mockMvc.perform(post("/api/v1/decompose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Make the URL shortener more reliable and faster")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.scenarioType").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.summary.taskCount").value(2))
                .andExpect(jsonPath("$.summary.ambiguityCount", greaterThan(0)));
    }

    @Test
    void rejectsBlankRequirement() throws Exception {
        mockMvc.perform(post("/api/v1/decompose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    @DisplayName("a plan can be fetched, rendered and scheduled after creation")
    void retrievesPlanRepresentations() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/decompose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Add click analytics and rate limiting to the existing service")))
                .andExpect(status().isOk())
                .andReturn();

        String planId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("plan").path("id").asText();

        mockMvc.perform(get("/api/v1/plans/{id}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.scenarioType").value("BROWNFIELD"));

        mockMvc.perform(get("/api/v1/plans/{id}/mermaid", planId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("graph TD")))
                .andExpect(content().string(containsString("IMPACT_ANALYSIS")));

        mockMvc.perform(get("/api/v1/plans/{id}/schedule", planId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Level 0")));

        mockMvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unknownPlanIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/plans/{id}", "plan-missing"))
                .andExpect(status().isNotFound());
    }
}
