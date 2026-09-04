package com.agentic.orchestrator.plan;

/**
 * A directed edge in the task graph: {@code from} must complete before {@code to} may start.
 *
 * <p>{@code reason} is not decoration. Every edge records why it exists, so the sequencing can be
 * defended rather than merely displayed.
 */
public record Dependency(String from, String to, DependencyKind kind, String reason) {

    public static Dependency data(String from, String to, String reason) {
        return new Dependency(from, to, DependencyKind.DATA, reason);
    }

    public static Dependency control(String from, String to, String reason) {
        return new Dependency(from, to, DependencyKind.CONTROL, reason);
    }
}
