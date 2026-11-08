package com.superagent.storage.jdbc;

import com.superagent.core.skill.Skill;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** ClickHouse skill store: self-creating {@code ReplacingMergeTree(updated_at)} tables; writes are inserts, reads use FINAL. */
public class ClickHouseSkillStore extends AbstractJdbcSkillStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS skill (
              company_id  String,
              id          String,
              name        String,
              description String,
              category    String,
              content     String,
              space       String,
              enabled     UInt8,
              deleted     UInt8,
              created_by  String,
              updated_by  String,
              created_at  DateTime64(3, 'UTC'),
              updated_at  DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (company_id, id)
            """,
            """
            CREATE TABLE IF NOT EXISTS skill_binding (
              company_id String,
              owner_type String,
              owner_id   String,
              skill_ids  String,
              updated_at DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (company_id, owner_type, owner_id)
            """,
            "ALTER TABLE skill ADD COLUMN IF NOT EXISTS space String");

    public ClickHouseSkillStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(Skill s) {
        jdbc.update("INSERT INTO skill " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", params(s));
    }

    @Override
    public void bind(String ownerType, String ownerId, List<String> skillIds) {
        jdbc.update("INSERT INTO skill_binding (company_id, owner_type, owner_id, skill_ids, updated_at) VALUES (?, ?, ?, ?, ?)",
                TenantSchema.company(), ownerType, ownerId, csv(skillIds), now());
    }
}
