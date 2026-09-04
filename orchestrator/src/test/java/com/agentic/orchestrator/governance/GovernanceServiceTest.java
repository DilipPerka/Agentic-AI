package com.agentic.orchestrator.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestrator.plan.AgentRole;
import com.agentic.orchestrator.plan.BlastRadius;
import com.agentic.orchestrator.plan.Stage;
import com.agentic.orchestrator.plan.Task;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GovernanceServiceTest {

    private final ApprovalMatrix matrix = new ApprovalMatrix();
    private final PolicyEngine policies = new PolicyEngine();
    private final GovernanceService governance = new GovernanceService(matrix, policies);

    private static Task task(BlastRadius radius, Stage stage, AgentRole role, Set<String> writes) {
        return new Task("T", "Task", "desc", stage, role, radius,
                List.of("done"), Set.of(), writes, false);
    }

    private static Task ordinary(BlastRadius radius) {
        return task(radius, Stage.IMPLEMENTATION, AgentRole.IMPLEMENTER, Set.of("SomeClass"));
    }

    @Nested
    @DisplayName("approval matrix")
    class Matrix {

        @Test
        @DisplayName("CRITICAL is never automatic, at any autonomy level")
        void criticalIsNeverAutomatic() {
            for (AutonomyLevel level : AutonomyLevel.values()) {
                assertThat(matrix.actionFor(level, BlastRadius.CRITICAL))
                        .as("CRITICAL at %s", level)
                        .isNotEqualTo(ControlAction.AUTO);
            }
        }

        @Test
        @DisplayName("raising autonomy never makes a cell stricter")
        void matrixIsMonotonic() {
            AutonomyLevel[] levels = AutonomyLevel.values();
            for (BlastRadius radius : BlastRadius.values()) {
                for (int i = 1; i < levels.length; i++) {
                    ControlAction looser = matrix.actionFor(levels[i], radius);
                    ControlAction stricter = matrix.actionFor(levels[i - 1], radius);
                    assertThat(looser.ordinal())
                            .as("%s at %s vs %s", radius, levels[i], levels[i - 1])
                            .isLessThanOrEqualTo(stricter.ordinal());
                }
            }
        }

        @Test
        void l0RequiresApprovalForEverythingItPermits() {
            assertThat(matrix.actionFor(AutonomyLevel.L0_OBSERVE, BlastRadius.LOW))
                    .isEqualTo(ControlAction.APPROVE);
        }

        @Test
        void l2IsTheDocumentedDefaultShape() {
            assertThat(matrix.actionFor(AutonomyLevel.L2_DELEGATED, BlastRadius.MEDIUM))
                    .isEqualTo(ControlAction.AUTO);
            assertThat(matrix.actionFor(AutonomyLevel.L2_DELEGATED, BlastRadius.HIGH))
                    .isEqualTo(ControlAction.APPROVE);
        }
    }

    @Nested
    @DisplayName("policies")
    class Policies {

        @Test
        void secretsAreDeniedOutright() {
            Task writesSecret = task(BlastRadius.LOW, Stage.IMPLEMENTATION,
                    AgentRole.IMPLEMENTER, Set.of("application-secret.yml"));

            assertThat(policies.evaluate(writesSecret))
                    .extracting(Policy::id).contains("SEC-01");
        }

        @Test
        void migrationsRequireApproval() {
            Task migration = task(BlastRadius.LOW, Stage.IMPLEMENTATION,
                    AgentRole.IMPLEMENTER, Set.of("migration:LINK_CREATION"));

            assertThat(policies.evaluate(migration)).extracting(Policy::id).contains("CHG-02");
        }

        @Test
        void buildConfigurationRequiresApproval() {
            Task buildChange = task(BlastRadius.LOW, Stage.IMPLEMENTATION,
                    AgentRole.IMPLEMENTER, Set.of("pom.xml"));

            assertThat(policies.evaluate(buildChange)).extracting(Policy::id).contains("CHG-01");
        }

        @Test
        void humanTasksRequireAHuman() {
            Task humanWork = task(BlastRadius.LOW, Stage.CLARIFICATION,
                    AgentRole.HUMAN, Set.of("artifact:clarification"));

            assertThat(policies.evaluate(humanWork)).extracting(Policy::id).contains("COM-01");
        }

        @Test
        void ordinaryWorkTriggersNothing() {
            assertThat(policies.evaluate(ordinary(BlastRadius.MEDIUM))).isEmpty();
        }
    }

    @Nested
    @DisplayName("combination")
    class Combination {

        @Test
        @DisplayName("autonomy can relax the matrix but never a policy")
        void policyOutranksAutonomy() {
            Task migration = task(BlastRadius.LOW, Stage.IMPLEMENTATION,
                    AgentRole.IMPLEMENTER, Set.of("migration:THING"));

            // LOW at L3 would be AUTO on the matrix alone.
            assertThat(matrix.actionFor(AutonomyLevel.L3_AUTONOMOUS, BlastRadius.LOW))
                    .isEqualTo(ControlAction.AUTO);
            // The policy still forces a human.
            assertThat(governance.evaluate(migration, AutonomyLevel.L3_AUTONOMOUS).action())
                    .isEqualTo(ControlAction.APPROVE);
        }

        @Test
        @DisplayName("a DENY policy cannot be dialled away by autonomy")
        void denyIsAbsolute() {
            Task writesSecret = task(BlastRadius.LOW, Stage.IMPLEMENTATION,
                    AgentRole.IMPLEMENTER, Set.of("db.password.properties"));

            for (AutonomyLevel level : AutonomyLevel.values()) {
                assertThat(governance.evaluate(writesSecret, level).action())
                        .as("secret write at %s", level)
                        .isEqualTo(ControlAction.DENY);
            }
        }

        @Test
        @DisplayName("the strictest signal wins")
        void takesTheStrictestSignal() {
            // Matrix says APPROVE (HIGH at L2); no policy denies; result stays APPROVE.
            assertThat(governance.evaluate(ordinary(BlastRadius.HIGH), AutonomyLevel.L2_DELEGATED)
                    .action()).isEqualTo(ControlAction.APPROVE);

            // Matrix says AUTO (LOW at L2) and nothing else fires.
            assertThat(governance.evaluate(ordinary(BlastRadius.LOW), AutonomyLevel.L2_DELEGATED)
                    .action()).isEqualTo(ControlAction.AUTO);
        }

        @Test
        @DisplayName("a WARN policy records a concern without impeding")
        void warningsDoNotBlock() {
            // CHG-03 fires on HIGH blast radius with effect WARN.
            GovernanceVerdict verdict =
                    governance.evaluate(ordinary(BlastRadius.HIGH), AutonomyLevel.L3_AUTONOMOUS);

            assertThat(verdict.triggered()).extracting(Policy::id).contains("CHG-03");
            assertThat(verdict.action()).isEqualTo(ControlAction.AUTO);
        }

        @Test
        @DisplayName("every verdict explains itself")
        void verdictsCarryReasons() {
            assertThat(governance.evaluate(ordinary(BlastRadius.HIGH), AutonomyLevel.L2_DELEGATED)
                    .reasons()).isNotEmpty();
            assertThat(governance.evaluate(ordinary(BlastRadius.LOW), AutonomyLevel.L2_DELEGATED)
                    .reasons()).isNotEmpty();
        }

        @Test
        void namesThePolicyThatDenied() {
            Task writesSecret = task(BlastRadius.LOW, Stage.IMPLEMENTATION,
                    AgentRole.IMPLEMENTER, Set.of("api.token.json"));

            assertThat(governance.evaluate(writesSecret, AutonomyLevel.L2_DELEGATED).denyingPolicy())
                    .isEqualTo("SEC-01");
        }
    }
}
