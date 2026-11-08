package com.superagent.platform.connector;

import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.api.integration.IntegrationDto;
import com.superagent.api.integration.SaveIntegrationRequest;
import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.security.SecretCipher;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.integration.IntegrationService;
import com.superagent.runtime.mcp.McpGrants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
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
    private final Map<String, String> settings = new LinkedHashMap<>();
    private final List<Object> published = new ArrayList<>();
    private IntegrationService integrations;
    private ConnectorService service;
    private final FakeGitee gitee = new FakeGitee();
    private SecretCipher cipher;

    /** Gitee as far as the refresh goes: hands out a new token, or refuses, or can't be reached. */
    private static final class FakeGitee implements OAuthProvider {
        String outcome = "ok";
        int refreshes;
        @Override public String key() { return "gitee"; }
        @Override public String authorizeUrl(String clientId, String redirectUri, String state) { return "https://gitee.test/oauth"; }
        @Override public Tokens exchange(String id, String secret, String code, String redirect) { return new Tokens("t0", "r0", Instant.now().plusSeconds(86400)); }
        @Override public Tokens refresh(String id, String secret, String refreshToken) throws IOException {
            refreshes++;
            return switch (outcome) {
                case "rejected" -> throw new TokenRejectedException("invalid_grant");
                case "offline" -> throw new IOException("connect timed out");
                default -> new Tokens("fresh-token", "r-" + refreshes, Instant.now().plusSeconds(86400));
            };
        }
        @Override public String fetchLogin(String accessToken) { return "ada-gitee"; }
    }

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
        SystemSettingStore settingStore = new SystemSettingStore() {
            @Override public Map<String, String> all() { return new LinkedHashMap<>(settings); }
            @Override public void put(String key, String value) { settings.put(key, value); }
        };
        cipher = new SecretCipher("", tmp.resolve("secret.key").toString());
        integrations = new IntegrationService(settingStore, cipher, published::add);
        ConnectorOAuthService oauth = new ConnectorOAuthService(store, creds, List.of(new GitHubOAuthClient(), gitee),
                cipher, integrations);
        service = new ConnectorService(store, creds, sources, oauth, cipher,
                new McpGrants("http://127.0.0.1:18080/mcp"), integrations, settingStore, new ConfluenceSite());
        service.seedDefaults(); // what each company's start does
    }

    @Test
    void listsSuperAgentsOwnMcpFirstAsReadOnly() {
        assertThat(service.list("ada", false).get(0)).satisfies(c -> {
            assertThat(c.id()).isEqualTo(ConnectorService.BUILTIN_ID);
            assertThat(c.kind()).isEqualTo("builtin");
            assertThat(c.needsAuthorization()).isFalse();
            assertThat(c.endpoint()).isNull(); // the URL is for administrators
        });
        assertThat(service.list("root", true).get(0).endpoint()).isEqualTo("http://127.0.0.1:18080/mcp");
        assertThatThrownBy(() -> service.delete(ConnectorService.BUILTIN_ID)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.update(ConnectorService.BUILTIN_ID, mcp("x", "none", "http", "https://x", null, null), "root"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void seedsGitHubAndGiteeAsPersonallyAuthorizedConnectors() {
        assertThat(service.list("ada", false)).filteredOn(c -> c.kind().equals("mcp")).extracting(ConnectorDto::id)
                .containsExactly("github", "gitee", "feishu-docs", "notion", "confluence", "dingtalk-tools");
        assertThat(service.list("ada", false)).filteredOn(c -> c.kind().equals("mcp") && !c.id().equals("dingtalk-tools")).allSatisfy(c -> {
            assertThat(c.needsAuthorization()).isTrue();
            assertThat(c.authorized()).isFalse();
            assertThat(c.endpoint()).isNull(); // configuration is for administrators
        });
        assertThat(service.mcpServersFor("ada")).isEmpty(); // not authorized: agents don't get it

        // They come with the system: marked so, not edited or deleted, only turned on and off.
        assertThat(service.list("ada", true)).filteredOn(c -> c.kind().equals("mcp")).allSatisfy(c -> assertThat(c.system()).isTrue());
        assertThatThrownBy(() -> service.delete("gitee")).isInstanceOf(com.superagent.core.error.ForbiddenException.class);
        assertThatThrownBy(() -> service.update("gitee", new SaveConnectorRequest("x", null, "oauth", "http", "https://evil", null, null, true), "admin"))
                .isInstanceOf(com.superagent.core.error.ForbiddenException.class);
        assertThat(service.setEnabled("dingtalk-tools", true, "admin").enabled()).isTrue();
        assertThat(connectors.get("gitee").endpoint()).isEqualTo("https://api.gitee.com/mcp");
    }

    @Test
    void giteeTokenIsRenewedBeforeItExpiresAndDroppedWhenGiteeRefusesIt() {
        integrations.update("gitee", new SaveIntegrationRequest(true, Map.of("clientId", "cid", "clientSecret", "csecret")), "http://sa");
        credentials.put("gitee/ada", new ConnectorCredential("gitee", "ada", cipher.encrypt("old-token"), "ada-gitee",
                Instant.now(), cipher.encrypt("r0"), Instant.now().plusSeconds(60))); // about to expire

        assertThat(service.mcpServersFor("ada")).singleElement().satisfies(s -> {
            assertThat(s.url()).isEqualTo("https://api.gitee.com/mcp");
            assertThat(s.headers()).containsEntry("Authorization", "Bearer fresh-token");
        });
        assertThat(cipher.decrypt(credentials.get("gitee/ada").refreshCipher())).isEqualTo("r-1"); // the new refresh token is kept
        service.mcpServersFor("ada");
        assertThat(gitee.refreshes).isEqualTo(1); // still fresh: not renewed again

        // Gitee unreachable while the token is still good: use it, try again next time.
        ConnectorCredential renewed = credentials.get("gitee/ada");
        credentials.put("gitee/ada", new ConnectorCredential("gitee", "ada", renewed.secretCipher(), renewed.accountLabel(),
                Instant.now(), renewed.refreshCipher(), Instant.now().plusSeconds(60)));
        gitee.outcome = "offline";
        assertThat(service.mcpServersFor("ada")).singleElement();
        // Gitee refuses the refresh token: the employee has to authorize again.
        gitee.outcome = "rejected";
        assertThat(service.mcpServersFor("ada")).isEmpty();
        assertThat(credentials).doesNotContainKey("gitee/ada");
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
    void integrationsLiveInSettingsWithSecretsEncryptedAndNeverShown() {
        assertThatThrownBy(() -> integrations.update("github", new SaveIntegrationRequest(true, Map.of("clientId", "Iv1")), "https://sa.example"))
                .hasMessageContaining("Client Secret");

        IntegrationDto gh = integrations.update("github",
                new SaveIntegrationRequest(true, Map.of("clientId", "Iv1", "clientSecret", "gh-s3cret")), "https://sa.example/");
        assertThat(gh.callbackUrl()).isEqualTo("https://sa.example/api/v1/oauth/github/callback");
        assertThat(gh.fields()).filteredOn(IntegrationDto.Field::secret).singleElement().satisfies(f -> {
            assertThat(f.value()).isNull();
            assertThat(f.set()).isTrue();
        });
        assertThat(settings.values()).noneMatch(v -> v != null && v.contains("gh-s3cret"));

        // A blank secret on save keeps the stored one; the browser never has it to send back.
        integrations.update("github", new SaveIntegrationRequest(true, Map.of("clientId", "Iv2", "clientSecret", "")), "x");
        assertThat(integrations.value("github", "clientSecret")).hasValue("gh-s3cret");
        assertThat(integrations.value("github", "clientId")).hasValue("Iv2");
        assertThat(published).isNotEmpty(); // what runs on an integration reconnects on this

        integrations.update("github", new SaveIntegrationRequest(false, Map.of("clientId", "Iv2")), "x");
        assertThat(integrations.value("github", "clientSecret")).isEmpty(); // disabled: nothing uses it
    }

    @Test
    void githubNeedsItsIntegrationAndFeishuAppearsOnceEnabled() {
        credentials.put("github/ada", new ConnectorCredential("github", "ada",
                new SecretCipher("", tmp.resolve("secret.key").toString()).encrypt("gho_ada"), "ada", null));
        assertThat(service.mcpServersFor("ada")).isEmpty(); // authorized, but the GitHub app is switched off
        integrations.update("github", new SaveIntegrationRequest(true, Map.of("clientId", "Iv1", "clientSecret", "s")), "x");
        assertThat(service.mcpServersFor("ada")).singleElement().satisfies(s -> assertThat(s.name()).isEqualTo("github"));

        assertThat(service.list("ada", false)).noneMatch(c -> c.kind().equals("feishu"));
        integrations.update("feishu", new SaveIntegrationRequest(true, Map.of("appId", "cli_a", "appSecret", "s")), "x");
        ConnectorDto mine = service.newBindCode(ConnectorService.FEISHU_ID, "ada", false);
        assertThat(mine.authorized()).isFalse();
        assertThat(mine.bindCode()).hasSize(8);

        links.put("ada", links.get("ada").withBinding("cli_a", "ou_ada")); // the bot received "绑定 <code>"
        assertThat(service.list("ada", false)).filteredOn(c -> c.kind().equals("feishu"))
                .singleElement().satisfies(c -> assertThat(c.authorized()).isTrue());
        assertThatThrownBy(() -> service.delete(ConnectorService.FEISHU_ID)).isInstanceOf(ForbiddenException.class);

        integrations.update("feishu", new SaveIntegrationRequest(true, Map.of("appId", "cli_new")), "x"); // ids differ per app
        assertThat(service.list("ada", false)).filteredOn(c -> c.kind().equals("feishu"))
                .singleElement().satisfies(c -> assertThat(c.authorized()).isFalse());
    }

    private static SaveConnectorRequest mcp(String name, String auth, String transport, String endpoint, String tokenEnv,
                                            String secret) {
        return new SaveConnectorRequest(name, null, auth, transport, endpoint, tokenEnv, secret, true);
    }

    @Test
    void feishuDefaultsToTheChinaDomainAndCanBeSwitchedToLark() {
        IntegrationDto f = integrations.update("feishu",
                new SaveIntegrationRequest(true, Map.of("appId", "cli_1", "appSecret", "s")), "https://sa.example");
        assertThat(integrations.ready("feishu")).isTrue(); // the domain has a default, so it's not required
        assertThat(integrations.value("feishu", "domain")).hasValue("feishu");
        assertThat(f.fields()).filteredOn(x -> x.key().equals("domain")).singleElement()
                .satisfies(x -> assertThat(x.options()).extracting(IntegrationDto.Option::value).containsExactly("feishu", "lark"));

        integrations.update("feishu", new SaveIntegrationRequest(true, Map.of("domain", "lark", "appId", "cli_1")), "https://sa.example");
        assertThat(integrations.value("feishu", "domain")).hasValue("lark");
        assertThat(integrations.value("feishu", "appSecret")).hasValue("s"); // kept
        assertThatThrownBy(() -> integrations.update("feishu",
                new SaveIntegrationRequest(true, Map.of("domain", "mars", "appId", "cli_1")), "https://sa.example"))
                .hasMessageContaining("must be one of");
    }

    @Test
    void confluenceChecksEachTokenAgainstTheSiteAndRunsAsThatPerson() throws IOException {
        // Not set up: listed, but nobody can authorize yet.
        assertThat(service.list("ada", false)).filteredOn(c -> c.id().equals("confluence")).singleElement()
                .satisfies(c -> assertThat(c.setUp()).isFalse());
        assertThatThrownBy(() -> service.authorize("confluence", "ada", "t", null, false)).hasMessageContaining("地址");

        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/rest/api/user/current", ex -> {
            boolean ok = "Bearer good-pat".equals(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (ok ? "{\"type\":\"known\",\"username\":\"ada.l\"}" : "").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 401, ok ? body.length : -1);
            if (ok) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        site.start();
        try {
            String url = "http://127.0.0.1:" + site.getAddress().getPort();
            integrations.update("confluence", new SaveIntegrationRequest(true, Map.of("url", url + "/")), "x");

            assertThatThrownBy(() -> service.authorize("confluence", "ada", "stale", null, false)).hasMessageContaining("无效");
            ConnectorDto mine = service.authorize("confluence", "ada", "good-pat", null, false);
            assertThat(mine.account()).isEqualTo("ada.l");
            assertThat(mine.site()).isEqualTo(url);
            assertThat(mine.needsAccount()).isFalse();

            assertThat(service.mcpServersFor("ada")).filteredOn(s -> s.name().equals("confluence")).singleElement()
                    .satisfies(s -> {
                        assertThat(s.command()).isEqualTo("uvx");
                        assertThat(s.env()).containsEntry("CONFLUENCE_URL", url).containsEntry("CONFLUENCE_PERSONAL_TOKEN", "good-pat");
                    });
            assertThat(service.mcpServersFor("bob")).noneMatch(s -> s.name().equals("confluence"));
        } finally {
            site.stop(0);
        }

        // Cloud: the site's Confluence lives under /wiki, and signing in takes the email with an API token.
        assertThat(ConfluenceSite.env("https://acme.atlassian.net/wiki/spaces/ENG", "ada@acme.com", "api-t"))
                .containsEntry("CONFLUENCE_URL", "https://acme.atlassian.net/wiki")
                .containsEntry("CONFLUENCE_USERNAME", "ada@acme.com").containsEntry("CONFLUENCE_API_TOKEN", "api-t")
                .doesNotContainKey("CONFLUENCE_PERSONAL_TOKEN");
        assertThatThrownBy(() -> new ConfluenceSite().verify("https://acme.atlassian.net", "", "api-t"))
                .hasMessageContaining("邮箱");
    }
}
