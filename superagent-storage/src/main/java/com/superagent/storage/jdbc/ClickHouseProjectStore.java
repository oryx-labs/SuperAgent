package com.superagent.storage.jdbc;

import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectMember;
import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse projects: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseProjectStore extends AbstractJdbcProjectStore {

    public ClickHouseProjectStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS project (
                  company_id String, id String, name String, description String, owner String, status String,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC'), archived_at Nullable(DateTime64(3, 'UTC'))
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, id)
                """, """
                CREATE TABLE IF NOT EXISTS project_member (
                  company_id String, project_id String, username String, added_by String, active UInt8,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, project_id, username)
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(Project p) {
        jdbc.update("INSERT INTO project " + PROJECT_INSERT, params(p));
    }

    @Override
    public void saveMember(ProjectMember m) {
        jdbc.update("INSERT INTO project_member " + MEMBER_INSERT, params(m));
    }
}
