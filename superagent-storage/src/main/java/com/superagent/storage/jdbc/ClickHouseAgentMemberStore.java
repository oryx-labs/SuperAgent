package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMember;
import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse agent members: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseAgentMemberStore extends AbstractJdbcAgentMemberStore {

    public ClickHouseAgentMemberStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS agent_member (
                  company_id String, agent_id String, username String, added_by String, active UInt8,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, agent_id, username)
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(AgentMember m) {
        jdbc.update("INSERT INTO agent_member " + INSERT, params(m));
    }
}
