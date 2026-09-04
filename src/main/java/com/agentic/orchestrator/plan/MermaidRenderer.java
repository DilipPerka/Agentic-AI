package com.agentic.orchestrator.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link Plan} as Mermaid graph source.
 *
 * <p>Text rather than an image on purpose: it is diffable, it pastes into any Markdown renderer, and
 * it costs nothing to keep in the API once a UI exists.
 */
@Component
public class MermaidRenderer {

    public String render(Plan plan) {
        StringBuilder out = new StringBuilder("graph TD\n");

        for (Task task : plan.tasks()) {
            out.append("  ").append(task.id()).append("[\"")
                    .append(escape(task.title()))
                    .append("<br/><small>").append(task.agentRole()).append("</small>")
                    .append(task.requiresHumanApproval() ? "<br/>&#128100; approval" : "")
                    .append("\"]\n");
        }

        for (Dependency dependency : plan.dependencies()) {
            // Dashed edges are ordering-only, so a reader can see which sequencing is inherent to
            // the requirement and which the planner imposed to avoid a collision.
            String arrow = dependency.kind() == DependencyKind.CONTROL ? "-.->" : "-->";
            out.append("  ").append(dependency.from()).append(' ').append(arrow).append(' ')
                    .append(dependency.to()).append('\n');
        }

        Map<BlastRadius, List<String>> byRadius = new LinkedHashMap<>();
        for (Task task : plan.tasks()) {
            byRadius.computeIfAbsent(task.blastRadius(), key -> new java.util.ArrayList<>())
                    .add(task.id());
        }

        out.append("  classDef low fill:#e8f5e9,stroke:#43a047,color:#1b5e20\n");
        out.append("  classDef medium fill:#fff8e1,stroke:#f9a825,color:#e65100\n");
        out.append("  classDef high fill:#ffebee,stroke:#e53935,color:#b71c1c\n");
        out.append("  classDef critical fill:#f3e5f5,stroke:#8e24aa,color:#4a148c\n");

        byRadius.forEach((radius, ids) -> out.append("  class ")
                .append(String.join(",", ids)).append(' ')
                .append(radius.name().toLowerCase(Locale.ROOT)).append('\n'));

        return out.toString();
    }

    /** Renders the sequencing as plain text — useful in a terminal, and in the write-up. */
    public String renderSchedule(Plan plan) {
        StringBuilder out = new StringBuilder();
        List<List<String>> levels = plan.executionLevels();
        for (int i = 0; i < levels.size(); i++) {
            List<String> level = levels.get(i);
            out.append("Level ").append(i).append(level.size() > 1 ? "  (parallel)" : "")
                    .append('\n');
            for (String id : level) {
                out.append("    - ").append(id).append('\n');
            }
        }
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("\"", "'");
    }

    /** Convenience for logs and tests. */
    public String summarise(Plan plan) {
        return plan.tasks().size() + " tasks, " + plan.dependencies().size() + " dependencies, "
                + plan.depth() + " levels, max parallelism " + plan.maxParallelism()
                + ", approvals: " + plan.tasks().stream()
                .filter(Task::requiresHumanApproval)
                .map(Task::id)
                .collect(Collectors.joining(", "));
    }
}
