package com.agentic.orchestrator.governance;

import com.agentic.orchestrator.plan.AgentRole;
import com.agentic.orchestrator.plan.BlastRadius;
import com.agentic.orchestrator.plan.Stage;
import com.agentic.orchestrator.plan.Task;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evaluates the policy set against a task.
 *
 * <p>Policies are absolute. Unlike the autonomy matrix, which an operator dials up and down, a
 * policy {@link Policy.Effect#DENY} halts the node regardless of autonomy level. That separation is
 * the whole point: autonomy expresses how much you trust the agents today, policy expresses what is
 * not on the table at all.
 */
@Component
public class PolicyEngine {

    /** Fragments that suggest a file holds a credential rather than code. */
    private static final Set<String> SECRET_MARKERS =
            Set.of("secret", "credential", "password", "keystore", ".env", "token", "private_key");

    /** Files that decide how everything else is built or shipped. */
    private static final Set<String> BUILD_MARKERS =
            Set.of("pom.xml", "build.gradle", "dockerfile", ".github/workflows", "jenkinsfile");

    private final List<Policy> policies = List.of(

            new Policy("SEC-01", "No agent may write credential material",
                    Policy.Category.SECURITY, Policy.Effect.DENY,
                    "Secrets written by an automated process end up in version control and cannot "
                            + "be un-leaked. There is no autonomy level at which this is acceptable.",
                    task -> writesMatch(task, SECRET_MARKERS)),

            new Policy("CHG-01", "Build and CI configuration changes require approval",
                    Policy.Category.CHANGE_CONTROL, Policy.Effect.REQUIRE_APPROVAL,
                    "Build configuration governs every subsequent action, including the checks that "
                            + "are supposed to catch mistakes. An agent editing it can disable its "
                            + "own guardrails.",
                    task -> writesMatch(task, BUILD_MARKERS)),

            new Policy("CHG-02", "Schema migrations require approval",
                    Policy.Category.CHANGE_CONTROL, Policy.Effect.REQUIRE_APPROVAL,
                    "A migration is hard to reverse once data exists. Reviewing it before it runs "
                            + "costs a minute; reversing it afterwards may be impossible.",
                    task -> task.writes().stream().anyMatch(key -> key.startsWith("migration:"))),

            new Policy("COM-01", "Work assigned to a human must not be performed by an agent",
                    Policy.Category.COMPLIANCE, Policy.Effect.REQUIRE_APPROVAL,
                    "A clarification or sign-off task exists precisely because a human judgement is "
                            + "needed. An agent completing it would defeat the reason it was planned.",
                    task -> task.agentRole() == AgentRole.HUMAN),

            new Policy("COM-02", "Release readiness requires a human sign-off",
                    Policy.Category.COMPLIANCE, Policy.Effect.REQUIRE_APPROVAL,
                    "Declaring something shippable is an accountability decision, not a technical "
                            + "one. It belongs to a person.",
                    task -> task.stage() == Stage.RELEASE_READINESS),

            new Policy("CHG-03", "Hot-path changes are flagged for review",
                    Policy.Category.CHANGE_CONTROL, Policy.Effect.WARN,
                    "Latency regressions on the request hot path are not caught by functional tests, "
                            + "so the reviewer should be told to look for them specifically.",
                    task -> task.blastRadius() == BlastRadius.HIGH)
    );

    public List<Policy> all() {
        return policies;
    }

    /** Every policy triggered by this task, in declaration order. */
    public List<Policy> evaluate(Task task) {
        return policies.stream().filter(policy -> policy.triggeredBy(task)).toList();
    }

    private static boolean writesMatch(Task task, Set<String> markers) {
        return task.writes().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(write -> markers.stream().anyMatch(write::contains));
    }
}
