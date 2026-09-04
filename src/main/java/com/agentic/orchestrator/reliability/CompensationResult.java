package com.agentic.orchestrator.reliability;

public record CompensationResult(boolean success, String summary) {

    public static CompensationResult undone(String summary) {
        return new CompensationResult(true, summary);
    }

    /**
     * Compensation failed. This is the most dangerous outcome in the system — the workspace is now
     * in a state neither the forward nor the reverse path intended — so it never auto-retries and
     * always escalates.
     */
    public static CompensationResult failed(String summary) {
        return new CompensationResult(false, summary);
    }
}
