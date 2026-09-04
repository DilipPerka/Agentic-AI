package com.agentic.orchestrator.plan;

/** Thrown when a produced task graph is structurally invalid. */
public class GraphValidationException extends RuntimeException {

    public GraphValidationException(String message) {
        super(message);
    }
}
