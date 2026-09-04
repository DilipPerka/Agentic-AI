package com.agentic.orchestrator.execution;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The outcome of evaluating a gate: satisfied only if no check failed.
 */
public record GateResult(String gate, boolean satisfied, List<CheckResult> checks) {

    public GateResult {
        checks = List.copyOf(checks);
    }

    public static GateResult of(String gate, List<CheckResult> checks) {
        return new GateResult(gate, checks.stream().noneMatch(CheckResult::failed), checks);
    }

    public List<CheckResult> failures() {
        return checks.stream().filter(CheckResult::failed).toList();
    }

    public String summary() {
        if (satisfied) {
            return gate + " satisfied (" + checks.size() + " checks)";
        }
        return gate + " failed: " + failures().stream()
                .map(check -> check.name() + " — " + check.message())
                .collect(Collectors.joining("; "));
    }
}
