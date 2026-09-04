package com.agentic.orchestrator.sandbox;

/**
 * An agent tried to act outside the boundary it is confined to.
 *
 * <p>Always a hard failure, never a warning. A path that escapes the workspace is either a bug in a
 * blueprint or an agent doing something it must not; both warrant stopping rather than continuing
 * with a narrowed action.
 */
public class SandboxViolationException extends RuntimeException {

    public SandboxViolationException(String message) {
        super(message);
    }
}
