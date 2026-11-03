package com.superagent.storage.jdbc;

import com.superagent.core.connector.Connector;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL connector store: upsert via {@code ON DUPLICATE KEY UPDATE}, hard delete. */
public class MysqlConnectorStore extends AbstractJdbcConnectorStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS connector (
              id          VARCHAR(64)  NOT NULL,
              name        VARCHAR(255) NOT NULL,
              description VARCHAR(1024),
              type        VARCHAR(16)  NOT NULL,
              auth_method VARCHAR(32)  NOT NULL,
              status      VARCHAR(32)  NOT NULL,
              transport   VARCHAR(16)  NOT NULL,
              endpoint    VARCHAR(2048),
              created_at  DATETIME(3)  NOT NULL,
              updated_at  DATETIME(3)  NOT NULL,
              PRIMARY KEY (id)
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
        jdbc.update("INSERT INTO connector "
                        + "(id, name, description, type, auth_method, status, transport, endpoint, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description), "
                        + "type=VALUES(type), auth_method=VALUES(auth_method), status=VALUES(status), "
                        + "transport=VALUES(transport), endpoint=VALUES(endpoint), updated_at=VALUES(updated_at)",
                c.id(), c.name(), nn(c.description()), c.type().name(), c.authMethod().name(),
                c.status().name(), c.transport().name(), nn(c.endpoint()), utc(c.createdAt()), utc(c.updatedAt()));
        return c;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM connector WHERE id = ?", id);
    }
}
