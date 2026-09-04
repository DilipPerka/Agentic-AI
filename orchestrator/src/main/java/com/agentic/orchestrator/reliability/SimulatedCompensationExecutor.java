package com.agentic.orchestrator.reliability;

import com.agentic.orchestrator.execution.ExecutionContext;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder compensation: reports the undo without performing one, because the forward executor is
 * simulated and there is nothing to undo yet.
 *
 * <p>What is real here is the ordering, the state machine and the escalation on failure. When the
 * real tool layer lands, only this class changes.
 *
 * <p>{@code orchestrator.simulation.compensation-failures} makes a specific node's compensation fail,
 * which is how the partial-rollback escalation is demonstrated rather than merely asserted.
 */
@Component
@ConditionalOnProperty(name = "orchestrator.executor", havingValue = "simulated",
        matchIfMissing = true)
public class SimulatedCompensationExecutor implements CompensationExecutor {

    private final Set<String> failingTasks;

    public SimulatedCompensationExecutor(
            @Value("${orchestrator.simulation.compensation-failures:}") String failingTasks) {
        this.failingTasks = parse(failingTasks);
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
    public CompensationResult compensate(ExecutionContext context) {
        String taskId = context.task().id();
        if (failingTasks.contains(taskId)) {
            return CompensationResult.failed(
                    "Could not undo " + taskId + "; workspace may be in a partial state");
        }
        if (context.task().writes().isEmpty()) {
            return CompensationResult.undone("Nothing to undo for " + taskId);
        }
        return CompensationResult.undone(
                "Reverted " + context.task().writes().size() + " output(s) of " + taskId);
    }
}
