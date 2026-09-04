package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.execution.NodeExecution;
import com.agentic.orchestrator.execution.NodeState;
import com.agentic.orchestrator.execution.Run;
import com.agentic.orchestrator.execution.RunStatus;
import com.agentic.orchestrator.governance.AutonomyLevel;
import com.agentic.orchestrator.replan.ReplanTrigger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RunStore {

    private final JdbcTemplate jdbc;
    private final Json json;

    public RunStore(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Writes the run header and every node row.
     *
     * <p>Rewriting all nodes on each transition rather than tracking which changed: a plan is tens
     * of nodes, so this is tens of upserts against an embedded database on an event that happens a
     * few dozen times per run. Dirty-tracking would be an optimisation whose bookkeeping is a
     * likelier source of bugs than the writes it saves. Worth revisiting only if plans get large.
     */
    public void save(Run run) {
        jdbc.update("""
                        MERGE INTO run (id, plan_id, autonomy_level, status, created_at, started_at,
                                        ended_at, stop_requested, stop_reason, paused,
                                        replan_count, pending_replan_guidance,
                                        pending_replan_trigger, base_run_id)
                        KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                run.id(),
                run.plan().id(),
                run.autonomyLevel().name(),
                run.status().name(),
                Timestamps.toSql(run.createdAt()),
                Timestamps.toSql(run.startedAt()),
                Timestamps.toSql(run.endedAt()),
                run.stopRequested(),
                run.stopReason(),
                run.paused(),
                run.replanCount(),
                run.pendingReplanGuidance(),
                run.pendingReplanTrigger() == null ? null : run.pendingReplanTrigger().name(),
                run.baseRunId());

        List<Object[]> rows = new ArrayList<>();
        for (NodeExecution node : run.nodes()) {
            rows.add(new Object[]{
                    run.id(),
                    node.taskId(),
                    node.state().name(),
                    node.attempt(),
                    Timestamps.toSql(node.startedAt()),
                    Timestamps.toSql(node.endedAt()),
                    node.message(),
                    json.write(node.outputs()),
                    node.fallbackAttempted(),
                    node.completedDegraded(),
                    node.fingerprint()});
        }

        jdbc.batchUpdate("""
                MERGE INTO run_node (run_id, task_id, state, attempt, started_at, ended_at,
                                     message, outputs, fallback_attempted, completed_degraded,
                                     fingerprint)
                KEY (run_id, task_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    /**
     * Removes node rows for tasks the run no longer has. Called after a re-plan adopts a new plan —
     * a MERGE cannot delete, so without this the table would accumulate nodes from every superseded
     * plan version and recovery would resurrect tasks that no longer exist.
     */
    public void pruneNodes(String runId, Collection<String> currentTaskIds) {
        if (currentTaskIds.isEmpty()) {
            jdbc.update("DELETE FROM run_node WHERE run_id = ?", runId);
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
                currentTaskIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(runId);
        args.addAll(currentTaskIds);
        jdbc.update("DELETE FROM run_node WHERE run_id = ? AND task_id NOT IN (" + placeholders + ")",
                args.toArray());
    }

    public List<PersistedRun> findAll() {
        List<PersistedRun> runs = jdbc.query(
                "SELECT * FROM run ORDER BY created_at",
                (rs, row) -> new PersistedRun(
                        rs.getString("id"),
                        rs.getString("plan_id"),
                        AutonomyLevel.valueOf(rs.getString("autonomy_level")),
                        RunStatus.valueOf(rs.getString("status")),
                        Timestamps.fromSql(rs, "created_at"),
                        Timestamps.fromSql(rs, "started_at"),
                        Timestamps.fromSql(rs, "ended_at"),
                        rs.getBoolean("stop_requested"),
                        rs.getString("stop_reason"),
                        rs.getBoolean("paused"),
                        rs.getInt("replan_count"),
                        rs.getString("pending_replan_guidance"),
                        rs.getString("pending_replan_trigger") == null
                                ? null
                                : ReplanTrigger.valueOf(rs.getString("pending_replan_trigger")),
                        rs.getString("base_run_id"),
                        List.of()));

        List<PersistedRun> withNodes = new ArrayList<>(runs.size());
        for (PersistedRun run : runs) {
            withNodes.add(new PersistedRun(
                    run.id(), run.planId(), run.autonomyLevel(), run.status(), run.createdAt(),
                    run.startedAt(), run.endedAt(), run.stopRequested(), run.stopReason(),
                    run.paused(), run.replanCount(), run.pendingReplanGuidance(),
                    run.pendingReplanTrigger(), run.baseRunId(), nodesOf(run.id())));
        }
        return withNodes;
    }

    private List<PersistedRun.PersistedNode> nodesOf(String runId) {
        return jdbc.query("SELECT * FROM run_node WHERE run_id = ?",
                (rs, row) -> new PersistedRun.PersistedNode(
                        rs.getString("task_id"),
                        NodeState.valueOf(rs.getString("state")),
                        rs.getInt("attempt"),
                        Timestamps.fromSql(rs, "started_at"),
                        Timestamps.fromSql(rs, "ended_at"),
                        rs.getString("message"),
                        json.readMap(rs.getString("outputs")),
                        rs.getBoolean("fallback_attempted"),
                        rs.getBoolean("completed_degraded"),
                        rs.getString("fingerprint")),
                runId);
    }
}
