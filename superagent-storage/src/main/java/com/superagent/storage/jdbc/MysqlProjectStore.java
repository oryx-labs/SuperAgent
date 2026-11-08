package com.superagent.storage.jdbc;

import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectMember;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL projects and their members: self-creating tables, upsert via REPLACE. */
public class MysqlProjectStore extends AbstractJdbcProjectStore {

    public MysqlProjectStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS project (
                  company_id  VARCHAR(64)  NOT NULL,
                  id          VARCHAR(64)  NOT NULL,
                  name        VARCHAR(255) NOT NULL,
                  description TEXT,
                  owner       VARCHAR(128) NOT NULL,
                  status      VARCHAR(16)  NOT NULL,
                  created_at  DATETIME(3)  NOT NULL,
                  updated_at  DATETIME(3)  NOT NULL,
                  archived_at DATETIME(3)  NULL,
                  PRIMARY KEY (company_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS project_member (
                  company_id VARCHAR(64)  NOT NULL,
                  project_id VARCHAR(64)  NOT NULL,
                  username   VARCHAR(128) NOT NULL,
                  added_by   VARCHAR(128),
                  active     TINYINT      NOT NULL,
                  created_at DATETIME(3)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, project_id, username),
                  KEY idx_project_member_user (company_id, username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(Project p) {
        jdbc.update("REPLACE INTO project " + PROJECT_INSERT, params(p));
    }

    @Override
    public void saveMember(ProjectMember m) {
        jdbc.update("REPLACE INTO project_member " + MEMBER_INSERT, params(m));
    }
}
