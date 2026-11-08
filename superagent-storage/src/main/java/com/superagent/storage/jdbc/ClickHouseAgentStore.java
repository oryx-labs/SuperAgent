package com.superagent.storage.jdbc;

import com.superagent.core.agent.Agent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse agent store: {@code ReplacingMergeTree(updated_at)} keyed by id, so
 * {@code update} re-inserts a newer version and reads use {@code FINAL}.
 */
public class ClickHouseAgentStore extends AbstractJdbcAgentStore {

    public ClickHouseAgentStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS agent (
                  company_id String,
                  id String,
                  name String,
                  avatar String,
                  character_key String,
                  persona String,
                  model String,
                  tier String,
                  status String,
                  owner String,
                  kind String,
                  managed_by String,
                  created_at DateTime64(3, 'UTC'),
                  updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, id)
                """));
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
