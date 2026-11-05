package com.superagent.platform.connector;

import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorServiceTest {

    /** In-memory {@link ConnectorStore} standing in for the JDBC backend. */
    private static final class InMemoryStore implements ConnectorStore {
        private final Map<String, Connector> rows = new LinkedHashMap<>();

        @Override public List<Connector> listConnectors() {
            return new ArrayList<>(rows.values());
        }
        @Override public Optional<Connector> findConnector(String id) {
            return Optional.ofNullable(rows.get(id));
        }
        @Override public Connector save(Connector connector) {
            rows.put(connector.id(), connector);
            return connector;
        }
        @Override public void delete(String id) {
            rows.remove(id);
        }
    }

    private static final class InMemoryCredentialStore implements ConnectorCredentialStore {
        private final Map<String, ConnectorCredential> rows = new LinkedHashMap<>();

        @Override public Optional<ConnectorCredential> find(String connectorId) {
            return Optional.ofNullable(rows.get(connectorId));
        }
        @Override public ConnectorCredential save(ConnectorCredential credential) {
            rows.put(credential.connectorId(), credential);
            return credential;
        }
        @Override public void delete(String connectorId) {
            rows.remove(connectorId);
        }
    }

    private static ConnectorService newService(ConnectorStore store, ConnectorCredentialStore credentials) {
        // Blank client id/secret: the OAuth flow stays unconfigured, which these CRUD/toggle tests never exercise.
        ConnectorOAuthService oauth = new ConnectorOAuthService(
                store, credentials, new GitHubOAuthClient(), "", "", "http://localhost:8080/cb");
        return new ConnectorService(store, oauth);
    }

    @Test
    void seedsDefaultsOnlyWhenStoreIsEmpty() {
        InMemoryStore store = new InMemoryStore();
        InMemoryCredentialStore creds = new InMemoryCredentialStore();

        ConnectorService first = newService(store, creds);
        int seeded = first.list().size();
        assertThat(seeded).isGreaterThan(0);
        assertThat(first.list()).allSatisfy(c -> assertThat(c.status()).isEqualTo("not_connected"));
        // GitHub ships as a real OAuth-handshake connector.
        assertThat(first.list()).anySatisfy(c -> assertThat(c.oauthProvider()).isEqualTo("github"));

        // A second instance over the same non-empty store must not re-seed.
        newService(store, creds);
        assertThat(store.listConnectors()).hasSize(seeded);
    }

    @Test
    void createAppliesDefaultsAndConnectTogglesStatus() {
        ConnectorService service = newService(new InMemoryStore(), new InMemoryCredentialStore());

        ConnectorDto created = service.create(
                new SaveConnectorRequest("My Server", "  ", null, null, null, "https://mcp.example.com"));
        assertThat(created.type()).isEqualTo("optional");
        assertThat(created.authMethod()).isEqualTo("system_oauth");
        assertThat(created.transport()).isEqualTo("stdio");
        assertThat(created.description()).isNull();
        assertThat(created.status()).isEqualTo("not_connected");
        assertThat(created.oauthProvider()).isNull(); // custom connectors have no OAuth handshake

        assertThat(service.connect(created.id()).status()).isEqualTo("connected");
        assertThat(service.disconnect(created.id()).status()).isEqualTo("not_connected");
    }

    @Test
    void updateKeepsStatusAndDeleteRemoves() {
        ConnectorService service = newService(new InMemoryStore(), new InMemoryCredentialStore());
        String id = service.create(new SaveConnectorRequest("S", null, "required", "personal_token", "http", "u")).id();
        service.connect(id);

        ConnectorDto updated = service.update(id,
                new SaveConnectorRequest("S2", "desc", "optional", "system_oauth", "sse", "u2"));
        assertThat(updated.name()).isEqualTo("S2");
        assertThat(updated.transport()).isEqualTo("sse");
        assertThat(updated.status()).isEqualTo("connected"); // status preserved across edits

        service.delete(id);
        assertThat(service.list()).noneMatch(c -> c.id().equals(id));
    }

    @Test
    void disconnectClearsStoredCredential() {
        InMemoryStore store = new InMemoryStore();
        InMemoryCredentialStore creds = new InMemoryCredentialStore();
        ConnectorService service = newService(store, creds);
        String id = service.create(new SaveConnectorRequest("S", null, null, null, null, "u")).id();

        creds.save(new ConnectorCredential(id, "secret-token", "octocat", java.time.Instant.now()));
        service.disconnect(id);

        assertThat(creds.find(id)).isEmpty();
    }

    @Test
    void missingConnectorYields404Exception() {
        ConnectorService service = newService(new InMemoryStore(), new InMemoryCredentialStore());
        assertThatThrownBy(() -> service.connect("nope")).isInstanceOf(ConnectorNotFoundException.class);
    }
}
