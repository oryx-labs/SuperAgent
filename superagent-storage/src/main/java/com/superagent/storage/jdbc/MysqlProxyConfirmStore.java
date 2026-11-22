package com.superagent.storage.jdbc;

import com.superagent.core.proxyconfirm.ConfirmType;
import com.superagent.core.proxyconfirm.ProxyConfirmRecord;
import com.superagent.core.proxyconfirm.ProxyConfirmRule;
import com.superagent.core.proxyconfirm.ProxyConfirmStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** MySQL proxy-confirm store: rules (per user, per project or global) and the records of what was confirmed. */
public class MysqlProxyConfirmStore implements ProxyConfirmStore {

    private final JdbcTemplate jdbc;

    public MysqlProxyConfirmStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS proxy_confirm_rule (
                  company_id VARCHAR(64)  NOT NULL,
                  owner      VARCHAR(255) NOT NULL,
                  project_id VARCHAR(64)  NOT NULL,
                  enabled    TINYINT(1)   NOT NULL,
                  types      VARCHAR(512) NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, owner, project_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS proxy_confirm_record (
                  company_id  VARCHAR(64)  NOT NULL,
                  id          VARCHAR(64)  NOT NULL,
                  owner       VARCHAR(255) NOT NULL,
                  project_id  VARCHAR(64)  NOT NULL,
                  type        VARCHAR(32)  NOT NULL,
                  object_type VARCHAR(32)  NOT NULL,
                  object_id   VARCHAR(64)  NOT NULL,
                  reason      TEXT         NOT NULL,
                  revocable   TINYINT(1)   NOT NULL,
                  status      VARCHAR(16)  NOT NULL,
                  at_time     DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, id),
                  KEY idx_proxy_confirm_owner (company_id, owner, at_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private final RowMapper<ProxyConfirmRule> ruleMapper = (rs, n) -> new ProxyConfirmRule(rs.getString("owner"),
            rs.getString("project_id"), rs.getBoolean("enabled"), parse(rs.getString("types")),
            rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final RowMapper<ProxyConfirmRecord> recordMapper = (rs, n) -> new ProxyConfirmRecord(rs.getString("id"),
            rs.getString("owner"), rs.getString("project_id"), ConfirmType.valueOf(rs.getString("type")),
            rs.getString("object_type"), rs.getString("object_id"), rs.getString("reason"), rs.getBoolean("revocable"),
            rs.getString("status"), rs.getObject("at_time", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    @Override
    public Optional<ProxyConfirmRule> findRule(String owner, String projectId) {
        return jdbc.query("SELECT * FROM proxy_confirm_rule WHERE company_id = ? AND owner = ? AND project_id = ?", ruleMapper,
                TenantSchema.company(), owner, projectId).stream().findFirst();
    }

    @Override
    public void saveRule(ProxyConfirmRule r) {
        jdbc.update("INSERT INTO proxy_confirm_rule (company_id, owner, project_id, enabled, types, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled), types=VALUES(types), updated_at=VALUES(updated_at)",
                TenantSchema.company(), r.owner(), r.projectId(), r.enabled(),
                r.types().stream().map(Enum::name).sorted().collect(Collectors.joining(",")), utc(r.updatedAt()));
    }

    @Override
    public void deleteRule(String owner, String projectId) {
        jdbc.update("DELETE FROM proxy_confirm_rule WHERE company_id = ? AND owner = ? AND project_id = ?",
                TenantSchema.company(), owner, projectId);
    }

    @Override
    public void saveRecord(ProxyConfirmRecord r) {
        jdbc.update("INSERT INTO proxy_confirm_record (company_id, id, owner, project_id, type, object_type, object_id, reason, "
                        + "revocable, status, at_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status=VALUES(status), object_id=VALUES(object_id)",
                TenantSchema.company(), r.id(), r.owner(), r.projectId() == null ? "" : r.projectId(), r.type().name(),
                r.objectType(), r.objectId(), r.reason(), r.revocable(), r.status(), utc(r.at()));
    }

    @Override
    public Optional<ProxyConfirmRecord> findRecord(String id) {
        return jdbc.query("SELECT * FROM proxy_confirm_record WHERE company_id = ? AND id = ?", recordMapper,
                TenantSchema.company(), id).stream().findFirst();
    }

    @Override
    public List<ProxyConfirmRecord> records(String owner, int limit) {
        return owner == null
                ? jdbc.query("SELECT * FROM proxy_confirm_record WHERE company_id = ? ORDER BY at_time DESC LIMIT ?",
                recordMapper, TenantSchema.company(), limit)
                : jdbc.query("SELECT * FROM proxy_confirm_record WHERE company_id = ? AND owner = ? ORDER BY at_time DESC LIMIT ?",
                recordMapper, TenantSchema.company(), owner, limit);
    }

    private static Set<ConfirmType> parse(String types) {
        Set<ConfirmType> out = EnumSet.noneOf(ConfirmType.class);
        Arrays.stream(types.split(",")).filter(s -> !s.isBlank()).map(ConfirmType::valueOf).forEach(out::add);
        return out;
    }

    private static LocalDateTime utc(java.time.Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
