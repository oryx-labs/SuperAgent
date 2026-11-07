package com.superagent.storage.jdbc;

import com.superagent.core.skill.SkillDeletion;
import com.superagent.core.skill.SkillDeletionStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** JDBC {@link SkillDeletionStore} shared by MySQL and ClickHouse. */
abstract class AbstractJdbcSkillDeletionStore implements SkillDeletionStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcSkillDeletionStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String INSERT = "(id, skill_id, skill_name, agent_id, requester, session_id, message_id, status, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(SkillDeletion d) {
        return new Object[]{d.id(), d.skillId(), nn(d.skillName()), nn(d.agentId()), d.requester(), nn(d.sessionId()),
                nn(d.messageId()), d.status().name(), utc(d.createdAt()), utc(d.updatedAt())};
    }

    private final RowMapper<SkillDeletion> mapper = (rs, n) -> new SkillDeletion(rs.getString("id"), rs.getString("skill_id"),
            emptyToNull(rs.getString("skill_name")), emptyToNull(rs.getString("agent_id")), rs.getString("requester"),
            emptyToNull(rs.getString("session_id")), emptyToNull(rs.getString("message_id")),
            SkillDeletion.Status.valueOf(rs.getString("status")),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    @Override
    public Optional<SkillDeletion> find(String id) {
        return jdbc.query("SELECT * FROM skill_deletion" + readFinal() + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    @Override
    public List<SkillDeletion> forAgent(String agentId) {
        return jdbc.query("SELECT * FROM skill_deletion" + readFinal() + " WHERE agent_id = ? ORDER BY created_at DESC",
                mapper, agentId);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i == null ? Instant.now() : i, ZoneOffset.UTC);
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
