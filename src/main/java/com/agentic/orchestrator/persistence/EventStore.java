package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.execution.EventType;
import com.agentic.orchestrator.execution.RunEvent;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventStore {

    private final JdbcTemplate jdbc;

    public EventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert, never merge. The log is append-only, so an event that already exists is a bug worth
     * hearing about as a primary-key violation rather than silently overwriting.
     */
    public void append(RunEvent event) {
        jdbc.update("""
                        INSERT INTO run_event (run_id, seq, task_id, type, actor, message, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                event.runId(), event.seq(), event.taskId(), event.type().name(),
                event.actor(), truncate(event.message()), Timestamps.toSql(event.timestamp()));
    }

    public List<RunEvent> findAll() {
        return jdbc.query("SELECT * FROM run_event ORDER BY run_id, seq",
                (rs, row) -> new RunEvent(
                        rs.getLong("seq"),
                        rs.getString("run_id"),
                        rs.getString("task_id"),
                        EventType.valueOf(rs.getString("type")),
                        rs.getString("actor"),
                        rs.getString("message"),
                        Timestamps.fromSql(rs, "occurred_at")));
    }

    /** Messages carry gate summaries, which can run long; the column is the limit, not the log. */
    private static String truncate(String message) {
        if (message == null || message.length() <= 2048) {
            return message;
        }
        return message.substring(0, 2045) + "...";
    }
}
