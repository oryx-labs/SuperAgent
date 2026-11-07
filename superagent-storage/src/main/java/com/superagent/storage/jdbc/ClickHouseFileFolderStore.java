package com.superagent.storage.jdbc;

import com.superagent.core.file.FileFolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/** ClickHouse folders and placements: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseFileFolderStore extends AbstractJdbcFileFolderStore {

    public ClickHouseFileFolderStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS file_folder (
                  id String, owner String, name String, parent_id String, deleted UInt8,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY id
                """, """
                CREATE TABLE IF NOT EXISTS file_placement (
                  owner String, file_id String, folder_id String, placed_by String, updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (owner, file_id)
                """));
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(FileFolder f) {
        jdbc.update("INSERT INTO file_folder " + FOLDER, params(f));
    }

    @Override
    public void place(String owner, String fileId, String folderId, String by) {
        jdbc.update("INSERT INTO file_placement " + PLACEMENT, owner, fileId, folderId, by, utc(Instant.now()));
    }
}
