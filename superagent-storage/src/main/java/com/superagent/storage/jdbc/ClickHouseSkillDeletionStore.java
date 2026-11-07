package com.superagent.storage.jdbc;

import com.superagent.core.skill.SkillDeletion;
import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse skill deletions: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseSkillDeletionStore extends AbstractJdbcSkillDeletionStore {

    public ClickHouseSkillDeletionStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS skill_deletion (
                  id String, skill_id String, skill_name String, agent_id String, requester String, session_id String,
                  message_id String, status String, created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY id
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(SkillDeletion d) {
        jdbc.update("INSERT INTO skill_deletion " + INSERT, params(d));
    }
}
