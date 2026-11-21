package com.superagent.storage.jdbc;

import com.superagent.core.handover.HandoverRecord;
import com.superagent.core.handover.HandoverRecordStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** MySQL handover audit rows: self-creating table, upsert via REPLACE. */
public class MysqlHandoverRecordStore implements HandoverRecordStore {

    private final JdbcTemplate jdbc;

    public MysqlHandoverRecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS handover_record (
                  company_id     VARCHAR(64)   NOT NULL,
                  id             VARCHAR(64)   NOT NULL,
                  from_user_id   VARCHAR(64)   NOT NULL,
                  from_username  VARCHAR(128)  NOT NULL,
                  to_user_id     VARCHAR(64)   NOT NULL,
                  to_username    VARCHAR(128)  NOT NULL,
                  by_user_id     VARCHAR(64)   NOT NULL,
                  status         VARCHAR(16)   NOT NULL,
                  summary_json   MEDIUMTEXT,
                  created_at     DATETIME(3)   NOT NULL,
                  finished_at    DATETIME(3),
                  PRIMARY KEY (company_id, id),
                  KEY idx_handover_created (company_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private final RowMapper<HandoverRecord> mapper = (rs, n) -> new HandoverRecord(
            rs.getString("id"),
            rs.getString("from_user_id"),
            rs.getString("from_username"),
            rs.getString("to_user_id"),
            rs.getString("to_username"),
            rs.getString("by_user_id"),
            rs.getString("status"),
            rs.getString("summary_json"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            Optional.ofNullable(rs.getObject("finished_at", LocalDateTime.class))
                    .map(t -> t.toInstant(ZoneOffset.UTC)).orElse(null));

    @Override
    public HandoverRecord save(HandoverRecord r) {
        jdbc.update("""
                REPLACE INTO handover_record
                (company_id, id, from_user_id, from_username, to_user_id, to_username, by_user_id, status,
                 summary_json, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TenantSchema.company(), r.id(), r.fromUserId(), r.fromUsername(), r.toUserId(), r.toUsername(),
                r.byUserId(), r.status(), r.summaryJson(), utc(r.createdAt()),
                r.finishedAt() == null ? null : utc(r.finishedAt()));
        return r;
    }

    @Override
    public Optional<HandoverRecord> find(String id) {
        return jdbc.query("SELECT * FROM handover_record WHERE company_id = ? AND id = ?", mapper,
                TenantSchema.company(), id).stream().findFirst();
    }

    @Override
    public List<HandoverRecord> list(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT * FROM handover_record WHERE company_id = ? ORDER BY created_at DESC LIMIT ?",
                mapper, TenantSchema.company(), n);
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
