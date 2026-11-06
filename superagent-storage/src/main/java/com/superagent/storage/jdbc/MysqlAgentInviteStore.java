package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentInvite;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL agent invitations: self-creating table, upsert via REPLACE. */
public class MysqlAgentInviteStore extends AbstractJdbcAgentInviteStore {

    public MysqlAgentInviteStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS agent_invite (
                  id         VARCHAR(64)   NOT NULL,
                  agent_id   VARCHAR(64)   NOT NULL,
                  owner      VARCHAR(190)  NOT NULL,
                  usernames  VARCHAR(2048) NOT NULL,
                  session_id VARCHAR(64),
                  message_id VARCHAR(64),
                  status     VARCHAR(16)   NOT NULL,
                  created_at DATETIME(3)   NOT NULL,
                  updated_at DATETIME(3)   NOT NULL,
                  PRIMARY KEY (id),
                  KEY idx_invite_agent (agent_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(AgentInvite i) {
        jdbc.update("REPLACE INTO agent_invite " + INSERT, params(i));
    }
}
