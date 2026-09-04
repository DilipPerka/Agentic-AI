package com.agentic.orchestrator.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Wipes all durable state.
 *
 * <p>Exists for test isolation and local resets, and is deliberately not reachable from the HTTP
 * API — an orchestrator that exposes "delete the entire audit trail" over REST has an audit trail in
 * name only.
 */
@Component
public class StateCleaner {

    private final JdbcTemplate jdbc;

    public StateCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteEverything() {
        // Children before parents, or the foreign keys reject it.
        jdbc.update("DELETE FROM decision");
        jdbc.update("DELETE FROM approval_request");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM run_node");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM plan");
    }
}
