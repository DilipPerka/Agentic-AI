package com.agentic.orchestrator.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Null-safe {@link Instant} conversion. Several columns are legitimately null until a run ends. */
public final class Timestamps {

    private Timestamps() {
    }

    public static Timestamp toSql(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant fromSql(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
