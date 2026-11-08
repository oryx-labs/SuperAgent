package com.superagent.storage.jdbc;

import com.superagent.core.connector.Connector;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL connector store: upsert via {@code REPLACE}, hard delete. */
public class MysqlConnectorStore extends AbstractJdbcConnectorStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS connector (
              company_id  VARCHAR(64)   NOT NULL,
              id          VARCHAR(64)   NOT NULL,
              name        VARCHAR(255)  NOT NULL,
              description VARCHAR(1024),
              kind        VARCHAR(16)   NOT NULL,
              auth_method VARCHAR(32)   NOT NULL,
              enabled     TINYINT       NOT NULL,
              transport   VARCHAR(16)   NOT NULL,
              endpoint    VARCHAR(2048),
              token_env   VARCHAR(128),
              created_at  DATETIME(3)   NOT NULL,
              updated_at  DATETIME(3)   NOT NULL,
              PRIMARY KEY (company_id, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlConnectorStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String connectorFinal() {
        return "";
    }

    @Override
    public Connector save(Connector c) {
        jdbc.update("REPLACE INTO connector " + COLUMNS + " VALUES " + VALUES, params(c));
        return c;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM connector WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
