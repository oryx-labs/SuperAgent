package com.superagent.storage.jdbc;

import com.superagent.core.agent.Agent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * MySQL agent store: {@code update} upserts via {@code ON DUPLICATE KEY UPDATE};
 * no {@code FINAL} clause is needed on reads. Creates its table on startup.
 */
public class MysqlAgentStore extends AbstractJdbcAgentStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS agent (
              id         VARCHAR(64)  NOT NULL,
              name       VARCHAR(255) NOT NULL,
              avatar     VARCHAR(32),
              character_key VARCHAR(64),
              persona    MEDIUMTEXT,
              model      VARCHAR(128),
              status     VARCHAR(32)  NOT NULL,
              owner      VARCHAR(255),
              kind       VARCHAR(16),
              managed_by VARCHAR(64),
              created_at DATETIME(3)  NOT NULL,
              updated_at DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_agent_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlAgentStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        // Older agent tables don't get new columns from CREATE TABLE IF NOT EXISTS.
        ensureColumn("avatar", "VARCHAR(32)");
        ensureColumn("character_key", "VARCHAR(64)");
        ensureColumn("owner", "VARCHAR(255)");
        ensureColumn("kind", "VARCHAR(16)");
        ensureColumn("managed_by", "VARCHAR(64)");
    }

    private void ensureColumn(String column, String type) {
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'agent' AND LOWER(column_name) = ?", Integer.class, column);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE agent ADD COLUMN " + column + " " + type);
        }
    }

    @Override
    protected String agentFinal() {
        return "";
    }

    @Override
    public void update(Agent a) {
        jdbc.update("INSERT INTO agent " + COLUMNS + VALUES + " "
                        + "ON DUPLICATE KEY UPDATE name=VALUES(name), avatar=VALUES(avatar), character_key=VALUES(character_key), persona=VALUES(persona), "
                        + "model=VALUES(model), status=VALUES(status), owner=VALUES(owner), kind=VALUES(kind), "
                        + "managed_by=VALUES(managed_by), updated_at=VALUES(updated_at)",
                params(a));
    }
}
