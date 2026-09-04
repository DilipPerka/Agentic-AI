package com.agentic.orchestrator.execution;

import java.util.Map;

/**
 * Outcome reported by a {@link NodeExecutor}.
 *
 * <p>This is the executor's <em>claim</em>, not a verdict. Success is granted by the exit gate,
 * which checks the claim against evidence.
 *
 * @param retryable whether another attempt could plausibly succeed. Defaulting to true is the safe
 *                  direction: a needless retry costs one attempt, whereas wrongly marking a
 *                  transient fault permanent turns a blip into a failed run.
 */
public record NodeResult(boolean success, String summary, Map<String, String> outputs,
                         boolean retryable) {

    public NodeResult {
        outputs = Map.copyOf(outputs);
    }

    public static NodeResult success(String summary) {
        return new NodeResult(true, summary, Map.of(), false);
    }

    public static NodeResult success(String summary, Map<String, String> outputs) {
        return new NodeResult(true, summary, outputs, false);
    }

    /** A failure that another attempt might survive. */
    public static NodeResult failure(String summary) {
        return new NodeResult(false, summary, Map.of(), true);
    }

    /** A failure that will recur however many times it is attempted. Skips straight past retry. */
    public static NodeResult permanentFailure(String summary) {
        return new NodeResult(false, summary, Map.of(), false);
    }
}
