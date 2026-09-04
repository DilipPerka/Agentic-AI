package com.agentic.orchestrator.execution;

/** One check within a gate. */
public record CheckResult(String name, Verdict verdict, String message) {

    public enum Verdict {
        PASS,
        FAIL,
        /**
         * A failure a human explicitly accepted, with a justification. Agents can never waive their
         * own checks — that would collapse the rule the gate exists to enforce.
         */
        WAIVED
    }

    public static CheckResult pass(String name, String message) {
        return new CheckResult(name, Verdict.PASS, message);
    }

    public static CheckResult fail(String name, String message) {
        return new CheckResult(name, Verdict.FAIL, message);
    }

    public boolean failed() {
        return verdict == Verdict.FAIL;
    }
}
