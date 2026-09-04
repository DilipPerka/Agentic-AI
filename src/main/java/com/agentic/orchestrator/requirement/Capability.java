package com.agentic.orchestrator.requirement;

import java.util.List;
import java.util.Set;

/**
 * A recognisable unit of product functionality that the planner knows how to expand into tasks.
 *
 * <p>The catalogue is what makes decomposition capability-driven rather than a fixed template: the
 * shape of the produced graph follows from which capabilities the requirement mentions and how they
 * depend on one another.
 *
 * @param dependsOn            ids of capabilities that must exist before this one can work
 * @param componentsTouched    code components this capability creates or modifies; used to detect
 *                             two tasks that would edit the same component in parallel
 */
public record Capability(
        String id,
        String displayName,
        List<String> keywords,
        boolean requiresSchemaChange,
        boolean touchesHotPath,
        Set<String> dependsOn,
        Set<String> componentsTouched) {

    public Capability {
        keywords = List.copyOf(keywords);
        dependsOn = Set.copyOf(dependsOn);
        componentsTouched = Set.copyOf(componentsTouched);
    }
}
