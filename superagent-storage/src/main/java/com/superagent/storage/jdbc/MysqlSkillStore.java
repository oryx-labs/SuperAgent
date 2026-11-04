package com.superagent.storage.jdbc;

import com.superagent.core.skill.Skill;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL skill store: self-creating tables, upserts via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlSkillStore extends AbstractJdbcSkillStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS skill (
              id          VARCHAR(64)  NOT NULL,
              name        VARCHAR(255) NOT NULL,
              description VARCHAR(1024),
              category    VARCHAR(64),
              content     MEDIUMTEXT,
              enabled     TINYINT      NOT NULL,
              deleted     TINYINT      NOT NULL,
              created_by  VARCHAR(128),
              updated_by  VARCHAR(128),
              created_at  DATETIME(3)  NOT NULL,
              updated_at  DATETIME(3)  NOT NULL,
              PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS skill_binding (
              owner_type VARCHAR(16) NOT NULL,
              owner_id   VARCHAR(64) NOT NULL,
              skill_ids  TEXT        NOT NULL,
              updated_at DATETIME(3) NOT NULL,
              PRIMARY KEY (owner_type, owner_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlSkillStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(Skill s) {
        jdbc.update("INSERT INTO skill " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description), category=VALUES(category), "
                + "content=VALUES(content), enabled=VALUES(enabled), deleted=VALUES(deleted), "
                + "updated_by=VALUES(updated_by), updated_at=VALUES(updated_at)", params(s));
    }

    @Override
    public void bind(String ownerType, String ownerId, List<String> skillIds) {
        jdbc.update("INSERT INTO skill_binding (owner_type, owner_id, skill_ids, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE skill_ids=VALUES(skill_ids), updated_at=VALUES(updated_at)",
                ownerType, ownerId, csv(skillIds), now());
    }
}
