package com.agentic.orchestrator.persistence;

import com.agentic.orchestrator.plan.Plan;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PlanStore {

    private final JdbcTemplate jdbc;
    private final Json json;

    public PlanStore(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(Plan plan) {
        // version and supersedes are duplicated out of the payload into columns so plan lineage is
        // queryable without deserialising every plan in the table.
        jdbc.update("""
                        MERGE INTO plan (id, created_at, payload, version, supersedes)
                        KEY (id) VALUES (?, ?, ?, ?, ?)
                        """,
                plan.id(), Timestamps.toSql(plan.createdAt()), json.write(plan),
                plan.version(), plan.supersedes());
    }

    public List<Plan> findAll() {
        return jdbc.query("SELECT payload FROM plan ORDER BY created_at",
                (rs, row) -> json.read(rs.getString("payload"), Plan.class));
    }

}
