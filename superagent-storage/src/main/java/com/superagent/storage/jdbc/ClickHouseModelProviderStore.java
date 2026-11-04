package com.superagent.storage.jdbc;

import com.superagent.core.provider.ModelProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse model-provider store: self-creating {@code ReplacingMergeTree(updated_at)}
 * table keyed by id; reads use {@code FINAL}, delete is an {@code ALTER ... DELETE}.
 */
public class ClickHouseModelProviderStore extends AbstractJdbcModelProviderStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS model_provider (
              id                String,
              provider_key      String,
              installed         UInt8,
              can_infer         UInt8,
              current_version   String,
              latest_version    String,
              status            String,
              last_checked_at   Nullable(DateTime64(3, 'UTC')),
              models_json       String,
              models_error      String,
              models_checked_at Nullable(DateTime64(3, 'UTC')),
              created_at        DateTime64(3, 'UTC'),
              updated_at        DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY id
            """,
            // Older tables predate the model-list columns.
            "ALTER TABLE model_provider ADD COLUMN IF NOT EXISTS models_json String",
            "ALTER TABLE model_provider ADD COLUMN IF NOT EXISTS models_error String",
            "ALTER TABLE model_provider ADD COLUMN IF NOT EXISTS models_checked_at Nullable(DateTime64(3, 'UTC'))");

    public ClickHouseModelProviderStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String providerFinal() {
        return " FINAL";
    }

    @Override
    public ModelProvider save(ModelProvider p) {
        insert(p);
        return p;
    }

    @Override
    public void delete(String id) {
        jdbc.update("ALTER TABLE model_provider DELETE WHERE id = ?", id);
    }
}
