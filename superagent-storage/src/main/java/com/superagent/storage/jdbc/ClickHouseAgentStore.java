package com.superagent.storage.jdbc;

import com.superagent.core.agent.Agent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse agent store: {@code ReplacingMergeTree(updated_at)} keyed by id, so
 * {@code update} re-inserts a newer version and reads use {@code FINAL}. Apply
 * {@code db/clickhouse/schema.sql} first.
 */
public class ClickHouseAgentStore extends AbstractJdbcAgentStore {

    public ClickHouseAgentStore(JdbcTemplate jdbc) {
        // Older tables predate the avatar column.
        super(jdbc, List.of("ALTER TABLE agent ADD COLUMN IF NOT EXISTS avatar String"));
    }

    @Override
    protected String agentFinal() {
        return " FINAL";
    }

    @Override
    public void update(Agent a) {
        insert(a);
    }
}
