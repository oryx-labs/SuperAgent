package com.superagent.storage.jdbc;

import com.superagent.core.notice.Notice;
import com.superagent.core.notice.NoticeStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** JDBC {@link NoticeStore} shared by MySQL and ClickHouse; backends differ in the upsert and FINAL. */
abstract class AbstractJdbcNoticeStore implements NoticeStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcNoticeStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String INSERT = "(id, owner, notice_key, kind, state, title, body, actor, link_type, link_id, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(Notice n) {
        return new Object[]{n.id(), n.owner(), n.key(), n.kind().name(), n.state().name(), nn(n.title()), nn(n.body()),
                nn(n.actor()), nn(n.linkType()), nn(n.linkId()), utc(n.createdAt()), utc(n.updatedAt())};
    }

    private final RowMapper<Notice> mapper = (rs, i) -> new Notice(rs.getString("id"), rs.getString("owner"),
            rs.getString("notice_key"), Notice.Kind.valueOf(rs.getString("kind")), Notice.State.valueOf(rs.getString("state")),
            rs.getString("title"), empty(rs.getString("body")), empty(rs.getString("actor")), empty(rs.getString("link_type")),
            empty(rs.getString("link_id")), instant(rs, "created_at"), instant(rs, "updated_at"));

    @Override
    public Optional<Notice> find(String id) {
        return jdbc.query("SELECT * FROM notice" + readFinal() + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    @Override
    public Optional<Notice> findByKey(String owner, String key) {
        return jdbc.query("SELECT * FROM notice" + readFinal() + " WHERE owner = ? AND notice_key = ?", mapper, owner, key)
                .stream().findFirst();
    }

    @Override
    public List<Notice> list(String owner, int limit) {
        return jdbc.query("SELECT * FROM notice" + readFinal() + " WHERE owner = ? AND kind <> 'TRACK' "
                + "ORDER BY updated_at DESC LIMIT ?", mapper, owner, limit);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i == null ? Instant.now() : i, ZoneOffset.UTC);
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String empty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
