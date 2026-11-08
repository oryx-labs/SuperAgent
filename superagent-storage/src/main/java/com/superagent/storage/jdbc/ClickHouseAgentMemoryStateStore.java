package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMemoryState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse agent-memory state store: self-creating {@code ReplacingMergeTree(updated_at)} keyed
 * by agent id; reads use {@code FINAL}, delete is an {@code ALTER ... DELETE}.
 */
public class ClickHouseAgentMemoryStateStore extends AbstractJdbcAgentMemoryStateStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS agent_memory (
              company_id       String,
              agent_id         String,
              owner            String,
              summarized_until Nullable(DateTime64(3, 'UTC')),
              last_model       String,
              session_failures Int32,
              memory           String,
              updated_at       DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (company_id, agent_id)
            """,
            // Older tables predate failure counting.
            "ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS session_failures Int32 DEFAULT 0",
            "ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS memory String");

    public ClickHouseAgentMemoryStateStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String stateFinal() {
        return " FINAL";
    }

    @Override
    public AgentMemoryState save(AgentMemoryState s) {
        jdbc.update("INSERT INTO agent_memory " + COLUMNS + VALUES, params(s));
        return s;
    }

    @Override
    public void delete(String agentId) {
        jdbc.update("ALTER TABLE agent_memory DELETE WHERE company_id = ? AND agent_id = ?", TenantSchema.company(), agentId);
    }
}
