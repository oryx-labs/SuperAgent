package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** ClickHouse agent run-log store: self-creating append-only {@code MergeTree}; retention via {@code ALTER ... DELETE}. */
public class ClickHouseAgentLogStore extends AbstractJdbcAgentLogStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS agent_log (
              id       String,
              agent_id String,
              owner    String,
              at_time  DateTime64(3, 'UTC'),
              level    String,
              category String,
              summary  String,
              detail   String
            ) ENGINE = MergeTree
            ORDER BY (agent_id, at_time)
            """);

    public ClickHouseAgentLogStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected void deleteWhere(String where, Object... args) {
        jdbc.update("ALTER TABLE agent_log DELETE WHERE " + where, args);
    }
}
