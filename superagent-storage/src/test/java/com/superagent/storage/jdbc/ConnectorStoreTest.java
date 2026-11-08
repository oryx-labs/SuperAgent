package com.superagent.storage.jdbc;

import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorAuthMethod;
import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorKind;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Connectors and their credentials are per company, though ids like "github" repeat across companies. */
@ExtendWith(InCompany.class)
class ConnectorStoreTest {

    private static JdbcTemplate h2() {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:connector-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static Connector github(String name) {
        Instant now = Instant.now();
        return new Connector("github", name, null, ConnectorKind.MCP, ConnectorAuthMethod.PERSONAL_TOKEN, true,
                ConnectorTransport.HTTP, "https://example.test/mcp", null, now, now);
    }

    @Test
    void connectorsAreKeptPerCompany() {
        MysqlConnectorStore store = new MysqlConnectorStore(h2());
        store.save(github("GitHub"));
        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.listConnectors()).isEmpty();
            store.save(github("Beta GitHub"));
            store.delete("github");
        });
        assertThat(store.findConnector("github")).hasValueSatisfying(c -> assertThat(c.name()).isEqualTo("GitHub"));
    }

    @Test
    void credentialsAreKeptPerCompany() {
        MysqlConnectorCredentialStore store = new MysqlConnectorCredentialStore(h2());
        store.save(new ConnectorCredential("github", "ada", "v1:acme", "ada@acme", Instant.now()));
        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.list("github")).isEmpty();
            store.save(new ConnectorCredential("github", "ada", "v1:beta", "ada@beta", Instant.now()));
            store.deleteAll("github");
        });
        assertThat(store.find("github", "ada")).hasValueSatisfying(c -> assertThat(c.secretCipher()).isEqualTo("v1:acme"));
    }
}
