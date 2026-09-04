package com.agentic.orchestrator.requirement;

public enum ScenarioType {
    /** New system or feature set, nothing assumed to exist. */
    GREENFIELD,
    /** Change to something that already exists; prerequisite capabilities are assumed present. */
    BROWNFIELD,
    /** No concrete capability could be extracted; clarification is required before planning. */
    AMBIGUOUS
}
