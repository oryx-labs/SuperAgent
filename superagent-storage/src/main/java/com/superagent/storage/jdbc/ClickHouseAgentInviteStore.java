package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentInvite;
import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse agent invitations: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseAgentInviteStore extends AbstractJdbcAgentInviteStore {

    public ClickHouseAgentInviteStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS agent_invite (
                  id String, agent_id String, owner String, usernames String, session_id String, message_id String,
                  status String, created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY id
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(AgentInvite i) {
        jdbc.update("INSERT INTO agent_invite " + INSERT, params(i));
    }
}
