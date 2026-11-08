package com.superagent.storage.jdbc;

import com.superagent.core.file.FileFolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/** MySQL folders and placements: self-creating tables, upsert via REPLACE. */
public class MysqlFileFolderStore extends AbstractJdbcFileFolderStore {

    public MysqlFileFolderStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS file_folder (
                  company_id VARCHAR(64)  NOT NULL,
                  id         VARCHAR(64)  NOT NULL,
                  owner      VARCHAR(190) NOT NULL,
                  name       VARCHAR(255) NOT NULL,
                  parent_id  VARCHAR(64)  NOT NULL DEFAULT '',
                  deleted    TINYINT      NOT NULL DEFAULT 0,
                  created_at DATETIME(3)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, id),
                  KEY idx_file_folder_owner (company_id, owner, deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS file_placement (
                  company_id VARCHAR(64)  NOT NULL,
                  owner      VARCHAR(190) NOT NULL,
                  file_id    VARCHAR(64)  NOT NULL,
                  folder_id  VARCHAR(64)  NOT NULL DEFAULT '',
                  placed_by  VARCHAR(16)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, owner, file_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """));
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(FileFolder f) {
        jdbc.update("REPLACE INTO file_folder " + FOLDER, params(f));
    }

    @Override
    public void place(String owner, String fileId, String folderId, String by) {
        jdbc.update("REPLACE INTO file_placement " + PLACEMENT, TenantSchema.company(), owner, fileId, folderId, by, utc(Instant.now()));
    }
}
