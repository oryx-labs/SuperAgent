package com.superagent.storage.jdbc;

import com.superagent.core.provider.ModelProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL model-provider store: self-creating table, upsert via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlModelProviderStore extends AbstractJdbcModelProviderStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS model_provider (
              company_id        VARCHAR(64) NOT NULL,
              id                VARCHAR(64) NOT NULL,
              provider_key      VARCHAR(64) NOT NULL,
              installed         TINYINT(1)  NOT NULL DEFAULT 0,
              can_infer         TINYINT(1)  NOT NULL DEFAULT 0,
              current_version   VARCHAR(64),
              latest_version    VARCHAR(64),
              status            VARCHAR(32) NOT NULL,
              last_checked_at   DATETIME(3),
              models_json       MEDIUMTEXT,
              models_error      VARCHAR(1024),
              models_checked_at DATETIME(3),
              created_at        DATETIME(3) NOT NULL,
              updated_at        DATETIME(3) NOT NULL,
              PRIMARY KEY (company_id, id),
              UNIQUE KEY uq_provider_key (company_id, provider_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlModelProviderStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        // Tables created before the model-list columns existed don't get them from CREATE TABLE IF NOT EXISTS.
        ensureColumn("models_json", "MEDIUMTEXT");
        ensureColumn("models_error", "VARCHAR(1024)");
        ensureColumn("models_checked_at", "DATETIME(3)");
    }

    private void ensureColumn(String column, String type) {
        Integer present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = SCHEMA() AND LOWER(table_name) = 'model_provider' "
                        + "AND LOWER(column_name) = LOWER(?)",
                Integer.class, column);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE model_provider ADD COLUMN " + column + " " + type);
        }
    }

    @Override
    protected String providerFinal() {
        return "";
    }

    @Override
    public ModelProvider save(ModelProvider p) {
        jdbc.update("INSERT INTO model_provider " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE provider_key=VALUES(provider_key), installed=VALUES(installed), "
                        + "can_infer=VALUES(can_infer), current_version=VALUES(current_version), "
                        + "latest_version=VALUES(latest_version), status=VALUES(status), "
                        + "last_checked_at=VALUES(last_checked_at), models_json=VALUES(models_json), "
                        + "models_error=VALUES(models_error), models_checked_at=VALUES(models_checked_at), "
                        + "updated_at=VALUES(updated_at)",
                params(p));
        return p;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM model_provider WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
