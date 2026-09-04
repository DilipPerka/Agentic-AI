package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.Decision;
import com.agentic.orchestrator.plan.BlastRadius;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class GovernanceStore {

    private final JdbcTemplate jdbc;
    private final Json json;

    public GovernanceStore(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Merge, not insert: an approval row is written when raised and again when decided. */
    public void save(ApprovalRequest request) {
        jdbc.update("""
                        MERGE INTO approval_request (id, run_id, task_id, task_title, blast_radius,
                                status, reasons, policy_ids, requested_at, decided_by, decided_at,
                                guidance)
                        KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                request.id(), request.runId(), request.taskId(), request.taskTitle(),
                request.blastRadius().name(), request.status().name(),
                json.write(request.reasons()), json.write(request.policyIds()),
                Timestamps.toSql(request.requestedAt()), request.decidedBy(),
                Timestamps.toSql(request.decidedAt()), request.guidance());
    }

    public List<ApprovalRequest> findAllApprovals() {
        return jdbc.query("SELECT * FROM approval_request ORDER BY requested_at",
                (rs, row) -> ApprovalRequest.restore(
                        rs.getString("id"),
                        rs.getString("run_id"),
                        rs.getString("task_id"),
                        rs.getString("task_title"),
                        BlastRadius.valueOf(rs.getString("blast_radius")),
                        json.readList(rs.getString("reasons")),
                        json.readList(rs.getString("policy_ids")),
                        Timestamps.fromSql(rs, "requested_at"),
                        ApprovalRequest.Status.valueOf(rs.getString("status")),
                        rs.getString("decided_by"),
                        Timestamps.fromSql(rs, "decided_at"),
                        rs.getString("guidance")));
    }

    public void save(Decision decision) {
        jdbc.update("""
                        INSERT INTO decision (id, run_id, task_id, actor, question, choice,
                                rationale, alternatives, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                decision.id(), decision.runId(), decision.taskId(), decision.actor(),
                decision.question(), decision.choice(), decision.rationale(),
                json.write(decision.alternatives()), Timestamps.toSql(decision.timestamp()));
    }

    public List<Decision> findAllDecisions() {
        return jdbc.query("SELECT * FROM decision ORDER BY occurred_at",
                (rs, row) -> new Decision(
                        rs.getString("id"),
                        rs.getString("run_id"),
                        rs.getString("task_id"),
                        rs.getString("actor"),
                        rs.getString("question"),
                        rs.getString("choice"),
                        rs.getString("rationale"),
                        json.readList(rs.getString("alternatives")),
                        Timestamps.fromSql(rs, "occurred_at")));
    }
}
