package com.agentic.orchestrator.execution;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder executor: simulates work so the engine can be exercised before real agents exist.
 *
 * <p>It is honest about what it is. It writes no files and runs no build, and its success is a claim
 * the exit gate still has to accept. Its value is that scheduling, parallelism, governance, retry,
 * fallback and rollback can all be built and proven against a deterministic executor, so when real
 * agents arrive the only new variable is the agent itself.
 *
 * <p>The injection properties exist so that failure handling can be <em>demonstrated</em> rather than
 * only unit-tested — a reviewer can watch a retry ladder or a timeout from the API without editing
 * code.
 */
@Component
@ConditionalOnProperty(name = "orchestrator.executor", havingValue = "simulated",
        matchIfMissing = true)
public class SimulatedNodeExecutor implements NodeExecutor {

    private final long delayMs;
    private final Set<String> failTasks;
    private final Set<String> permanentFailTasks;
    private final Set<String> omitEvidenceTasks;
    private final Set<String> slowTasks;
    private final long slowTaskDelayMs;

    public SimulatedNodeExecutor(
            @Value("${orchestrator.simulation.node-delay-ms:40}") long delayMs,
            @Value("${orchestrator.simulation.fail-tasks:}") String failTasks,
            @Value("${orchestrator.simulation.permanent-fail-tasks:}") String permanentFailTasks,
            @Value("${orchestrator.simulation.omit-evidence-tasks:}") String omitEvidenceTasks,
            @Value("${orchestrator.simulation.slow-tasks:}") String slowTasks,
            @Value("${orchestrator.simulation.slow-task-delay-ms:60000}") long slowTaskDelayMs) {
        this.delayMs = delayMs;
        this.failTasks = parse(failTasks);
        this.permanentFailTasks = parse(permanentFailTasks);
        this.omitEvidenceTasks = parse(omitEvidenceTasks);
        this.slowTasks = parse(slowTasks);
        this.slowTaskDelayMs = slowTaskDelayMs;
    }

    private static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList());
    }

    @Override
    public NodeResult execute(ExecutionContext context) {
        String taskId = context.task().id();

        if (!sleep(slowTasks.contains(taskId) ? slowTaskDelayMs : delayMs)) {
            return NodeResult.failure("Interrupted");
        }

        if (permanentFailTasks.contains(taskId)) {
            return NodeResult.permanentFailure(
                    "Permanent failure for " + taskId + "; retrying would not help");
        }

        // Fails on normal attempts but succeeds when degraded — the shape of a real fallback, where
        // the full implementation cannot be produced but a reduced one can.
        if (failTasks.contains(taskId) && !context.degraded()) {
            return NodeResult.failure(
                    "Injected failure for " + taskId + " (attempt " + context.attempt() + ")");
        }

        String summary = (context.degraded() ? "Degraded " : "Simulated ")
                + context.task().agentRole() + " work for " + taskId;

        if (omitEvidenceTasks.contains(taskId)) {
            // Reports success while producing nothing. The exit gate should refuse it — that is the
            // whole point of the gate, and it needs to be demonstrable rather than asserted.
            return NodeResult.success(summary + " (no evidence produced)");
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        for (String declaredOutput : context.task().writes()) {
            outputs.put(declaredOutput, (context.degraded() ? "degraded:" : "simulated:") + taskId);
        }
        return NodeResult.success(summary, outputs);
    }

    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
