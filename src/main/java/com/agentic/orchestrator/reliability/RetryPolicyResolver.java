package com.agentic.orchestrator.reliability;

import com.agentic.orchestrator.plan.AgentRole;
import com.agentic.orchestrator.plan.BlastRadius;
import com.agentic.orchestrator.plan.Task;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chooses a retry policy per task.
 *
 * <p>Retrying is not universally safe, so this is a decision rather than a constant:
 * <ul>
 *   <li><b>Human tasks never retry.</b> Automatically re-running a clarification or a sign-off is
 *       meaningless — the person did not fail, they have not answered yet.
 *   <li><b>High and critical blast radius never retry.</b> A schema migration or a hot-path change
 *       that failed once may have left something behind. Repeating it automatically doubles the
 *       chance of damage while removing the human who should be looking at it. It fails to a person
 *       instead.
 *   <li><b>Everything else gets bounded retries with backoff.</b> Ordinary work fails for ordinary
 *       transient reasons, and a second attempt is cheaper than a human.
 * </ul>
 */
@Component
public class RetryPolicyResolver {

    private final int maxAttempts;
    private final Duration initialBackoff;

    public RetryPolicyResolver(
            @Value("${orchestrator.retry.max-attempts:3}") int maxAttempts,
            @Value("${orchestrator.retry.initial-backoff-ms:200}") long initialBackoffMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = Duration.ofMillis(Math.max(0, initialBackoffMs));
    }

    public RetryPolicy policyFor(Task task) {
        if (task.agentRole() == AgentRole.HUMAN) {
            return RetryPolicy.NONE;
        }
        if (task.blastRadius() == BlastRadius.HIGH || task.blastRadius() == BlastRadius.CRITICAL) {
            return RetryPolicy.NONE;
        }
        return RetryPolicy.of(maxAttempts, initialBackoff);
    }
}
