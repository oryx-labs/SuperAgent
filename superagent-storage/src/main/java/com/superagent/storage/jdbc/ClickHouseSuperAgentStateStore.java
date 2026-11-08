package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse SuperAgent state store: self-creating {@code ReplacingMergeTree(updated_at)} keyed by
 * owner; save re-inserts a newer version and reads use {@code FINAL}.
 */
public class ClickHouseSuperAgentStateStore extends AbstractJdbcSuperAgentStateStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS superagent_state (
              company_id      String,
              owner           String,
              agent_id        String,
              processed_until Nullable(DateTime64(3, 'UTC')),
              plan_id         String,
              plan_status     String,
              plan_json       String,
              tracked_json    String,
              updated_at      DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (company_id, owner)
            """);

    public ClickHouseSuperAgentStateStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String stateFinal() {
        return " FINAL";
    }

    @Override
    public SuperAgentState save(SuperAgentState s) {
        jdbc.update("INSERT INTO superagent_state " + COLUMNS + VALUES, params(s));
        return s;
    }
}
