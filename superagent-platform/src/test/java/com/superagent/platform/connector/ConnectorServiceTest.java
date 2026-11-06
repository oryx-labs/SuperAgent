package com.superagent.platform.connector;

import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.core.error.ConflictException;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorServiceTest {

    @TempDir
    Path tmp;

    private final Map<String, Connector> connectors = new LinkedHashMap<>();
    private final Map<String, ConnectorCredential> credentials = new LinkedHashMap<>();
    private final Map<String, EventSource> links = new LinkedHashMap<>();
    private final List<Object> published = new ArrayList<>();
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        ConnectorStore store = new ConnectorStore() {
            @Override public List<Connector> listConnectors() { return new ArrayList<>(connectors.values()); }
            @Override public Optional<Connector> findConnector(String id) { return Optional.ofNullable(connectors.get(id)); }
            @Override public Connector save(Connector c) { connectors.put(c.id(), c); return c; }
            @Override public void delete(String id) { connectors.remove(id); }
        };
        ConnectorCredentialStore creds = new ConnectorCredentialStore() {
            @Override public Optional<ConnectorCredential> find(String id, String owner) {
                return Optional.ofNullable(credentials.get(id + "/" + owner));
            }
            @Override public List<ConnectorCredential> list(String id) {
                return credentials.values().stream().filter(c -> c.connectorId().equals(id)).toList();
            }
            @Override public ConnectorCredential save(ConnectorCredential c) {
                credentials.put(c.connectorId() + "/" + c.owner(), c);
                return c;
            }
            @Override public void delete(String id, String owner) { credentials.remove(id + "/" + owner); }
            @Override public void deleteAll(String id) { credentials.values().removeIf(c -> c.connectorId().equals(id)); }
        };
        EventSourceStore sources = new EventSourceStore() {
            @Override public Optional<EventSource> find(String owner, String type) { return Optional.ofNullable(links.get(owner)); }
            @Override public List<EventSource> listEnabled(String type) { return list(); }
            @Override public List<EventSource> list() { return new ArrayList<>(links.values()); }
            @Override public void save(EventSource s) { links.put(s.owner(), s); }
        };
        SecretCipher cipher = new SecretCipher("", tmp.resolve("secret.key").toString());
        // Blank client id/secret: GitHub OAuth stays unconfigured, which these tests never go through.
        ConnectorOAuthService oauth = new ConnectorOAuthService(store, creds, new GitHubOAuthClient(), cipher, "", "",
                "http://localhost/cb");
        service = new ConnectorService(store, creds, sources, oauth, cipher, published::add);
    }

    @Test
    void seedsGitHubOnceAsAPersonallyAuthorizedConnector() {
        assertThat(service.list("ada", false)).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("github");
            assertThat(c.needsAuthorization()).isTrue();
            assertThat(c.authorized()).isFalse();
            assertThat(c.endpoint()).isNull(); // configuration is for administrators
        });
        assertThat(service.mcpServersFor("ada")).isEmpty(); // not authorized: agents don't get it
    }

    @Test
    void sharedTokenServerReachesEveryRunWithoutLeakingTheToken() {
        assertThatThrownBy(() -> service.create(mcp("Docs", "shared_token", "http", "https://docs.example/mcp", null, null), "root"))
                .hasMessageContaining("Token is required");

        ConnectorDto docs = service.create(mcp("Docs", "shared_token", "http", "https://docs.example/mcp", null, "s3cret"), "root");

        assertThat(docs.companySecretSet()).isTrue();
        assertThat(credentials.values()).allSatisfy(c -> assertThat(c.secretCipher()).doesNotContain("s3cret"));
        assertThat(service.mcpServersFor("ada")).singleElement().satisfies(s -> {
            assertThat(s.url()).isEqualTo("https://docs.example/mcp");
            assertThat(s.headers()).containsEntry("Authorization", "Bearer s3cret");
            assertThat(s.name()).matches("[A-Za-z0-9_-]{1,64}");
        });
        assertThat(ConnectorService.toolPatterns(service.mcpServersFor("ada"))).allMatch(t -> t.startsWith("mcp__"));
    }

    @Test
    void personalTokenIsUsedOnlyForItsOwner() {
        assertThatThrownBy(() -> service.create(mcp("Jira", "personal_token", "stdio", "npx -y jira-mcp", null, null), "root"))
                .hasMessageContaining("environment variable");
        ConnectorDto jira = service.create(mcp("Jira", "personal_token", "stdio", "npx -y jira-mcp", "JIRA_TOKEN", null), "root");

        service.authorize(jira.id(), "ada", "ada-token", false);

        List<McpServerSpec> forAda = service.mcpServersFor("ada").stream().filter(s -> !"github".equals(s.name())).toList();
        assertThat(forAda).singleElement().satisfies(s -> {
            assertThat(s.command()).isEqualTo("npx");
            assertThat(s.args()).containsExactly("-y", "jira-mcp");
            assertThat(s.env()).containsEntry("JIRA_TOKEN", "ada-token");
        });
        assertThat(service.mcpServersFor("bob")).isEmpty();
        assertThat(service.list("root", true)).filteredOn(c -> c.id().equals(jira.id()))
                .singleElement().satisfies(c -> assertThat(c.authorizedCount()).isEqualTo(1));

        service.revoke(jira.id(), "ada", false);
        assertThat(service.mcpServersFor("ada")).isEmpty();
    }

    @Test
    void feishuIsOneCompanyAppAndEmployeesLinkByCode() {
        ConnectorDto feishu = service.create(feishu("cli_a", "app-secret"), "root");
        assertThatThrownBy(() -> service.create(feishu("cli_b", "x"), "root")).isInstanceOf(ConflictException.class);
        assertThat(published).isNotEmpty(); // the event module reconnects on this

        ConnectorDto mine = service.newBindCode(feishu.id(), "ada", false);
        assertThat(mine.authorized()).isFalse();
        assertThat(mine.bindCode()).hasSize(8);

        links.put("ada", links.get("ada").withBinding("cli_a", "ou_ada")); // the bot received "绑定 <code>"
        assertThat(service.list("ada", false)).filteredOn(c -> c.kind().equals("feishu"))
                .singleElement().satisfies(c -> assertThat(c.authorized()).isTrue());

        service.update(feishu.id(), feishu("cli_new", null), "root"); // another app: ids there differ
        assertThat(service.list("ada", false)).filteredOn(c -> c.kind().equals("feishu"))
                .singleElement().satisfies(c -> assertThat(c.authorized()).isFalse());
        assertThat(service.mcpServersFor("ada")).isEmpty(); // Feishu gives no agent tools
    }

    private static SaveConnectorRequest mcp(String name, String auth, String transport, String endpoint, String tokenEnv,
                                            String secret) {
        return new SaveConnectorRequest(name, null, "mcp", auth, transport, endpoint, tokenEnv, null, secret, true);
    }

    private static SaveConnectorRequest feishu(String appId, String secret) {
        return new SaveConnectorRequest(null, null, "feishu", null, null, null, null, appId, secret, true);
    }
}
