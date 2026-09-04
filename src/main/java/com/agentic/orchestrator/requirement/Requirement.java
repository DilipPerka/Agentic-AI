package com.agentic.orchestrator.requirement;

import java.util.List;

/**
 * A raw request after normalisation: what was asked, what it was understood to mean, and what
 * remains unclear.
 *
 * @param capabilities    concrete capabilities that will be planned as work
 * @param assumedExisting capability ids treated as already present in the codebase (brownfield);
 *                        these are prerequisites the requirement depends on but did not ask for
 * @param ambiguities     under-specified terms that a human should resolve
 */
public record Requirement(
        String rawText,
        String normalisedStatement,
        ScenarioType scenarioType,
        List<Capability> capabilities,
        List<String> assumedExisting,
        List<Ambiguity> ambiguities) {

    public Requirement {
        capabilities = List.copyOf(capabilities);
        assumedExisting = List.copyOf(assumedExisting);
        ambiguities = List.copyOf(ambiguities);
    }

    public boolean isPlannable() {
        return !capabilities.isEmpty();
    }
}
