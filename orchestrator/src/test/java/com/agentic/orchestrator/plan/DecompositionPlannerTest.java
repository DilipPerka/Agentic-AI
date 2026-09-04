package com.agentic.orchestrator.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestrator.requirement.CapabilityCatalog;
import com.agentic.orchestrator.requirement.Requirement;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import com.agentic.orchestrator.requirement.ScenarioType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DecompositionPlannerTest {

    private final RequirementNormalizer normalizer =
            new RequirementNormalizer(new CapabilityCatalog());
    private final DecompositionPlanner planner = new DecompositionPlanner();

    private Plan plan(String requirement) {
        return planner.decompose(normalizer.normalize(requirement));
    }

    private static List<String> ids(Plan plan) {
        return plan.tasks().stream().map(Task::id).toList();
    }

    private static int levelOf(Plan plan, String taskId) {
        List<List<String>> levels = plan.executionLevels();
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).contains(taskId)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasEdge(Plan plan, String from, String to) {
        return plan.dependencies().stream()
                .anyMatch(edge -> edge.from().equals(from) && edge.to().equals(to));
    }

    @Nested
    @DisplayName("greenfield")
    class Greenfield {

        private final Plan plan = plan("Build a URL shortener service with shorten and redirect APIs");

        @Test
        void coversTheFullLifecycle() {
            assertThat(ids(plan)).contains(
                    DecompositionPlanner.REQ_NORMALISE,
                    DecompositionPlanner.ARCH_BASELINE,
                    "DESIGN_" + CapabilityCatalog.LINK_CREATION,
                    "IMPL_" + CapabilityCatalog.LINK_CREATION,
                    "TEST_" + CapabilityCatalog.LINK_CREATION,
                    DecompositionPlanner.INTEGRATION_TEST,
                    DecompositionPlanner.DOCUMENTATION,
                    DecompositionPlanner.CODE_REVIEW,
                    DecompositionPlanner.RELEASE_READINESS);
        }

        @Test
        @DisplayName("omits the conditional stages that do not apply")
        void hasNoImpactAnalysisOrClarification() {
            assertThat(ids(plan))
                    .doesNotContain(DecompositionPlanner.IMPACT_ANALYSIS, DecompositionPlanner.CLARIFY);
        }

        @Test
        @DisplayName("capability dependencies become task ordering")
        void ordersRedirectAfterLinkCreation() {
            assertThat(hasEdge(plan,
                    "IMPL_" + CapabilityCatalog.LINK_CREATION,
                    "IMPL_" + CapabilityCatalog.REDIRECT)).isTrue();

            assertThat(levelOf(plan, "IMPL_" + CapabilityCatalog.REDIRECT))
                    .isGreaterThan(levelOf(plan, "IMPL_" + CapabilityCatalog.LINK_CREATION));
        }

        @Test
        @DisplayName("independent feature designs dispatch in parallel")
        void designBranchesRunInParallel() {
            assertThat(levelOf(plan, "DESIGN_" + CapabilityCatalog.LINK_CREATION))
                    .isEqualTo(levelOf(plan, "DESIGN_" + CapabilityCatalog.REDIRECT));
            assertThat(plan.maxParallelism()).isGreaterThan(1);
        }

        @Test
        @DisplayName("documentation depends on implementation, not on testing")
        void documentationDoesNotQueueBehindTests() {
            assertThat(hasEdge(plan, DecompositionPlanner.INTEGRATION_TEST,
                    DecompositionPlanner.DOCUMENTATION)).isFalse();
            assertThat(hasEdge(plan, "IMPL_" + CapabilityCatalog.REDIRECT,
                    DecompositionPlanner.DOCUMENTATION)).isTrue();
        }

        @Test
        @DisplayName("schema and hot-path work is flagged for approval")
        void marksHighRiskTasksForApproval() {
            assertThat(plan.tasks())
                    .filteredOn(Task::requiresHumanApproval)
                    .extracting(Task::id)
                    .contains("SCHEMA_" + CapabilityCatalog.LINK_CREATION,
                            "IMPL_" + CapabilityCatalog.REDIRECT,
                            DecompositionPlanner.RELEASE_READINESS);
        }

        @Test
        void everyTaskHasAcceptanceCriteria() {
            assertThat(plan.tasks()).allSatisfy(task ->
                    assertThat(task.acceptanceCriteria()).isNotEmpty());
        }

        @Test
        @DisplayName("every dependency records why it exists")
        void everyEdgeIsJustified() {
            assertThat(plan.dependencies()).allSatisfy(edge ->
                    assertThat(edge.reason()).isNotBlank());
        }
    }

    @Nested
    @DisplayName("brownfield")
    class Brownfield {

        private final Plan plan = plan("Add click analytics and rate limiting to the existing service");

        @Test
        void insertsImpactAnalysisBeforeDesign() {
            assertThat(ids(plan)).contains(DecompositionPlanner.IMPACT_ANALYSIS);
            assertThat(hasEdge(plan, DecompositionPlanner.IMPACT_ANALYSIS,
                    DecompositionPlanner.ARCH_BASELINE)).isTrue();
            assertThat(levelOf(plan, DecompositionPlanner.IMPACT_ANALYSIS))
                    .isLessThan(levelOf(plan, DecompositionPlanner.ARCH_BASELINE));
        }

        @Test
        @DisplayName("does not re-plan capabilities that already exist")
        void doesNotRebuildPrerequisites() {
            assertThat(ids(plan)).doesNotContain(
                    "IMPL_" + CapabilityCatalog.LINK_CREATION,
                    "IMPL_" + CapabilityCatalog.REDIRECT);
            assertThat(plan.requirement().assumedExisting())
                    .contains(CapabilityCatalog.LINK_CREATION, CapabilityCatalog.REDIRECT);
        }

        @Test
        @DisplayName("serialises the two features that both edit RedirectController")
        void serialisesWriteConflict() {
            String analytics = "IMPL_" + CapabilityCatalog.CLICK_ANALYTICS;
            String rateLimiting = "IMPL_" + CapabilityCatalog.RATE_LIMITING;

            assertThat(plan.dependencies())
                    .filteredOn(edge -> edge.kind() == DependencyKind.CONTROL)
                    .extracting(Dependency::to)
                    .contains(rateLimiting);

            assertThat(levelOf(plan, rateLimiting)).isNotEqualTo(levelOf(plan, analytics));
        }

        @Test
        @DisplayName("the reason for the imposed ordering is recorded, not implicit")
        void explainsTheImposedOrdering() {
            assertThat(plan.planningNotes())
                    .anySatisfy(note -> assertThat(note).contains("RedirectController"));
            assertThat(plan.planningNotes())
                    .anySatisfy(note -> assertThat(note).contains("Assumed already present"));
        }
    }

    @Nested
    @DisplayName("ambiguous")
    class Ambiguous {

        private final Plan plan = plan("Make the URL shortener more reliable and faster");

        @Test
        @DisplayName("stops at a human clarification task instead of inventing work")
        void haltsForClarification() {
            assertThat(ids(plan)).containsExactly(
                    DecompositionPlanner.REQ_NORMALISE, DecompositionPlanner.CLARIFY);
            assertThat(plan.requirement().scenarioType()).isEqualTo(ScenarioType.AMBIGUOUS);
        }

        @Test
        void clarificationIsOwnedByAHuman() {
            Task clarify = plan.tasks().stream()
                    .filter(task -> task.id().equals(DecompositionPlanner.CLARIFY))
                    .findFirst()
                    .orElseThrow();

            assertThat(clarify.agentRole()).isEqualTo(AgentRole.HUMAN);
            assertThat(clarify.requiresHumanApproval()).isTrue();
        }

        @Test
        void explainsWhyPlanningStopped() {
            assertThat(plan.planningNotes())
                    .anySatisfy(note -> assertThat(note).contains("no concrete capability"));
        }
    }

    @Test
    @DisplayName("the same requirement produces a different shape per scenario")
    void scenarioChangesTheGraphShape() {
        String text = "Add click analytics";
        Requirement asGreenfield = normalizer.normalize(text, ScenarioType.GREENFIELD);
        Requirement asBrownfield = normalizer.normalize(text, ScenarioType.BROWNFIELD);

        Plan greenfield = planner.decompose(asGreenfield);
        Plan brownfield = planner.decompose(asBrownfield);

        // Greenfield must build the prerequisites; brownfield assumes them and adds impact analysis.
        assertThat(greenfield.tasks().size()).isGreaterThan(brownfield.tasks().size());
        assertThat(ids(greenfield)).contains("IMPL_" + CapabilityCatalog.REDIRECT);
        assertThat(ids(brownfield)).contains(DecompositionPlanner.IMPACT_ANALYSIS);
    }

    @Test
    @DisplayName("produced graphs are always valid")
    void everyPlanIsAValidGraph() {
        List<String> requirements = List.of(
                "Build a URL shortener service with shorten and redirect APIs",
                "Add click analytics and rate limiting to the existing service",
                "Add caching and authentication with api key",
                "Make the URL shortener more reliable and faster",
                "Add expiry and custom alias support plus click tracking");

        for (String requirement : requirements) {
            Plan produced = plan(requirement);
            // Reconstructing the graph re-runs cycle and reference validation.
            TaskGraph graph = TaskGraph.of(produced.tasks(), produced.dependencies());
            assertThat(graph.executionLevels()).isNotEmpty();
            assertThat(produced.depth()).isPositive();
        }
    }
}
