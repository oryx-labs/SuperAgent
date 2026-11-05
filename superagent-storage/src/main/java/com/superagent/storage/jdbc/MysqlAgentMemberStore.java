package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMember;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL agent members: self-creating table, upsert via REPLACE. */
public class MysqlAgentMemberStore extends AbstractJdbcAgentMemberStore {

    public MysqlAgentMemberStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS agent_member (
                  agent_id   VARCHAR(64)  NOT NULL,
                  username   VARCHAR(128) NOT NULL,
                  added_by   VARCHAR(128),
                  active     TINYINT      NOT NULL,
                  created_at DATETIME(3)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (agent_id, username),
                  KEY idx_member_user (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(AgentMember m) {
        jdbc.update("REPLACE INTO agent_member " + INSERT, params(m));
    }
}
