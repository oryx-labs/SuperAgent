package com.superagent.storage.jdbc;

import com.superagent.core.skill.SkillDeletion;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL skill deletions: self-creating table, upsert via REPLACE. */
public class MysqlSkillDeletionStore extends AbstractJdbcSkillDeletionStore {

    public MysqlSkillDeletionStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS skill_deletion (
                  id         VARCHAR(64)   NOT NULL,
                  skill_id   VARCHAR(64)   NOT NULL,
                  skill_name VARCHAR(255),
                  agent_id   VARCHAR(64),
                  requester  VARCHAR(190)  NOT NULL,
                  session_id VARCHAR(64),
                  message_id VARCHAR(64),
                  status     VARCHAR(16)   NOT NULL,
                  created_at DATETIME(3)   NOT NULL,
                  updated_at DATETIME(3)   NOT NULL,
                  PRIMARY KEY (id),
                  KEY idx_skill_deletion_agent (agent_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(SkillDeletion d) {
        jdbc.update("REPLACE INTO skill_deletion " + INSERT, params(d));
    }
}
