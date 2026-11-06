package com.superagent.platform.connector;

import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorAuthMethod;
import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorKind;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.security.SecretCipher;
import com.superagent.platform.integration.IntegrationService;
import com.superagent.runtime.mcp.McpGrants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Connectors: how the company links to outside systems, as each employee sees them. Listed first is SuperAgent's
 * own MCP, then Feishu when its integration is enabled (it brings messages to each employee's SuperAgent; the
 * app itself is set up under System Settings), then the MCP servers an administrator added for agents
 * ({@link #mcpServersFor}). Employees authorize the ones that act with a personal identity. Secrets are
 * encrypted and never leave this service.
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DIGEST_TIME = "09:00";
    /** SuperAgent's own MCP: always handed to agent runs, shown first on the list, never editable. */
    public static final String BUILTIN_ID = "superagent";
    /** The Feishu row: shown while the Feishu integration is enabled; configured only under System Settings. */
    public static final String FEISHU_ID = "feishu";

    private final ConnectorStore store;
    private final ConnectorCredentialStore credentials;
    private final EventSourceStore links;
    private final ConnectorOAuthService oauth;
    private final SecretCipher cipher;
    private final McpGrants grants;
    private final IntegrationService integrations;
    private final com.superagent.core.settings.SystemSettingStore settings;
    private final SecureRandom random = new SecureRandom();

    public ConnectorService(ConnectorStore store, ConnectorCredentialStore credentials, EventSourceStore links,
                            ConnectorOAuthService oauth, SecretCipher cipher,
                            McpGrants grants, IntegrationService integrations,
                            com.superagent.core.settings.SystemSettingStore settings) {
        this.store = store;
        this.credentials = credentials;
        this.links = links;
        this.oauth = oauth;
        this.cipher = cipher;
        this.grants = grants;
        this.integrations = integrations;
        this.settings = settings;
        seedDefaults();
    }

    public List<ConnectorDto> list(String viewer, boolean admin) {
        List<ConnectorDto> out = new ArrayList<>();
        out.add(builtin(admin));
        if (integrations.ready(IntegrationService.FEISHU)) {
            out.add(feishuRow(viewer, admin));
        }
        store.listConnectors().forEach(c -> out.add(toDto(c, viewer, admin)));
        return out;
    }

    /**
     * SuperAgent's own MCP (tasks, files, skills, automations, the team): found at startup from the port the
     * server listens on and checked by calling it, then handed to every run with a per-turn token. Listed so
     * people can see it is there and working; nothing about it can be changed.
     */
    private ConnectorDto builtin(boolean admin) {
        McpGrants.SelfStatus st = grants.selfStatus();
        String status = switch (st.state()) {
            case "ok" -> "connected";
            case "error" -> "error";
            default -> "connecting";
        };
        return new ConnectorDto(BUILTIN_ID, "SuperAgent", null, "builtin", "none", true, "http", status,
                admin ? st.error() : null, false, true, null, null, false, admin ? grants.selfUrl() : null, null,
                false, 0, null, st.checkedAt());
    }

    /** Feishu as an employee sees it: whether they're linked, and the code to link if matching by phone didn't. */
    private ConnectorDto feishuRow(String viewer, boolean admin) {
        String appId = integrations.value(IntegrationService.FEISHU, "appId").orElse(null);
        EventSource link = links.find(viewer, EventSource.FEISHU).orElse(null);
        boolean linked = link != null && link.linkedTo(appId);
        int count = admin ? (int) links.list().stream()
                .filter(s -> EventSource.FEISHU.equals(s.type()) && s.linkedTo(appId)).count() : 0;
        return new ConnectorDto(FEISHU_ID, "飞书", null, "feishu", "none", true, "http",
                integrations.status(IntegrationService.FEISHU), admin ? integrations.lastError(IntegrationService.FEISHU) : null,
                true, linked, null, linked || link == null ? null : link.bindCode(), false, null, null, false, count,
                null, null);
    }

    // --- administration ---

    public ConnectorDto create(SaveConnectorRequest request, String viewer) {
        Instant now = Instant.now();
        Connector connector = validated(new Connector(UUID.randomUUID().toString(), null, null, ConnectorKind.MCP, null,
                true, null, null, null, now, now), request);
        requireSecret(connector, request);
        store.save(connector);
        saveCompanySecret(connector, request.secret());
        log.info("created connector {} ({})", connector.id(), connector.name());
        return toDto(connector, viewer, true);
    }

    public ConnectorDto update(String id, SaveConnectorRequest request, String viewer) {
        Connector existing = require(id);
        Connector updated = validated(existing, request);
        if (updated.authMethod() != existing.authMethod()) {
            credentials.deleteAll(id); // authorizations made for another method no longer apply
        }
        if (blank(request.secret()) == null && credentials.find(id, ConnectorCredential.COMPANY).isEmpty()) {
            requireSecret(updated, request);
        }
        store.save(updated);
        saveCompanySecret(updated, request.secret());
        return toDto(updated, viewer, true);
    }

    public void delete(String id) {
        require(id);
        store.delete(id);
        credentials.deleteAll(id);
    }

    // --- an employee's own authorization ---

    /** Saves the viewer's personal token for a connector that acts with each employee's own identity. */
    public ConnectorDto authorize(String id, String viewer, String token, boolean admin) {
        Connector c = require(id);
        if (c.authMethod() != ConnectorAuthMethod.PERSONAL_TOKEN) {
            throw new IllegalArgumentException("This connector doesn't take a personal token");
        }
        if (blank(token) == null) {
            throw new IllegalArgumentException("Token is required");
        }
        credentials.save(new ConnectorCredential(id, viewer, cipher.encrypt(token.strip()), null, Instant.now()));
        return toDto(c, viewer, admin);
    }

    public ConnectorDto revoke(String id, String viewer, boolean admin) {
        Connector c = require(id);
        if (!c.authMethod().personal()) {
            throw new IllegalArgumentException("Nothing to revoke for this connector");
        }
        credentials.delete(id, viewer);
        return toDto(c, viewer, admin);
    }

    /** A fresh code the viewer sends the company Feishu bot ("绑定 XXXX") to link themselves when matching by phone didn't. */
    public ConnectorDto newBindCode(String id, String viewer, boolean admin) {
        if (!FEISHU_ID.equals(id) || !integrations.ready(IntegrationService.FEISHU)) {
            throw new IllegalArgumentException("Binding is only for Feishu, once it is enabled in System Settings");
        }
        String code = bindCode();
        links.save(links.find(viewer, EventSource.FEISHU).map(s -> s.withBindCode(code))
                .orElseGet(() -> EventSource.create(viewer, EventSource.FEISHU, null, null, code, DIGEST_TIME,
                        ZONE.getId(), LocalDate.now(ZONE))));
        return feishuRow(viewer, admin);
    }

    // --- what agent runs use ---

    /**
     * The MCP servers an agent working for {@code owner} gets: every enabled MCP connector whose
     * authorization is in place (the company's token, or the owner's own). Ones the owner hasn't
     * authorized are left out rather than failing the run.
     */
    public List<McpServerSpec> mcpServersFor(String owner) {
        List<McpServerSpec> out = new ArrayList<>();
        for (Connector c : store.listConnectors()) {
            if (c.kind() != ConnectorKind.MCP || !c.enabled() || c.endpoint() == null) {
                continue;
            }
            if (c.authMethod() == ConnectorAuthMethod.OAUTH && !oauth.isConfigured(oauth.providerFor(c.id()))) {
                continue; // its app is switched off under System Settings
            }
            String token = null;
            if (c.authMethod() != ConnectorAuthMethod.NONE) {
                String who = c.authMethod().personal() ? owner : ConnectorCredential.COMPANY;
                Optional<ConnectorCredential> cred = credentials.find(c.id(), who);
                if (cred.isEmpty()) {
                    continue;
                }
                // OAuth tokens may expire (Gitee's after a day): the OAuth service renews them first.
                Optional<String> usable = c.authMethod() == ConnectorAuthMethod.OAUTH ? oauth.accessToken(cred.get())
                        : decrypt(c, cred.get());
                if (usable.isEmpty()) {
                    continue;
                }
                token = usable.get();
            }
            out.add(spec(c, token));
        }
        return out;
    }

    private Optional<String> decrypt(Connector c, ConnectorCredential cred) {
        try {
            return Optional.of(cipher.decrypt(cred.secretCipher()));
        } catch (IllegalStateException e) {
            log.warn("connector {}: stored token can't be decrypted, skipping it", c.id());
            return Optional.empty();
        }
    }

    /** The tool allowlist entries that let an agent call every tool of these servers. */
    public static List<String> toolPatterns(List<McpServerSpec> servers) {
        return servers.stream().map(s -> "mcp__" + s.name()).toList();
    }

    private static McpServerSpec spec(Connector c, String token) {
        String name = SERVER_ID.matcher(c.id()).matches() && !"superagent".equals(c.id())
                ? c.id() : "c_" + c.id().replace("-", "").substring(0, Math.min(12, c.id().replace("-", "").length()));
        if (c.transport() == ConnectorTransport.STDIO) {
            List<String> parts = Arrays.stream(c.endpoint().strip().split("\\s+")).toList();
            Map<String, String> env = token != null && c.tokenEnv() != null ? Map.of(c.tokenEnv(), token) : Map.of();
            return new McpServerSpec(name, c.transport(), null, Map.of(), parts.get(0), parts.subList(1, parts.size()), env);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return new McpServerSpec(name, c.transport(), c.endpoint(), headers, null, List.of(), Map.of());
    }

    // --- validation ---

    /** {@code base} with the request applied; the kind never changes after creation. */
    private Connector validated(Connector base, SaveConnectorRequest r) {
        boolean enabled = r.enabled() == null ? base.enabled() : r.enabled();
        Instant now = Instant.now();
        ConnectorAuthMethod method = parse(ConnectorAuthMethod.class, r.authMethod(), ConnectorAuthMethod.NONE);
        if (method == ConnectorAuthMethod.OAUTH && oauth.providerFor(base.id()) == null) {
            throw new IllegalArgumentException("OAuth is only available for built-in connectors");
        }
        ConnectorTransport transport = parse(ConnectorTransport.class, r.transport(), ConnectorTransport.STDIO);
        String endpoint = blank(r.endpoint());
        if (endpoint == null) {
            throw new IllegalArgumentException(transport == ConnectorTransport.STDIO ? "Command is required" : "URL is required");
        }
        if (transport != ConnectorTransport.STDIO && !endpoint.matches("(?i)https?://\\S+")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
        String tokenEnv = blank(r.tokenEnv());
        if (transport == ConnectorTransport.STDIO && method != ConnectorAuthMethod.NONE) {
            if (tokenEnv == null || !ENV_NAME.matcher(tokenEnv).matches()) {
                throw new IllegalArgumentException("A stdio server needs the name of the environment variable it reads its token from");
            }
        } else {
            tokenEnv = null;
        }
        return new Connector(base.id(), cleanName(r.name(), "MCP"), blank(r.description()), ConnectorKind.MCP, method,
                enabled, transport, endpoint, tokenEnv, base.createdAt(), now);
    }

    private void requireSecret(Connector c, SaveConnectorRequest r) {
        if (blank(r.secret()) != null) {
            return;
        }
        if (c.authMethod() == ConnectorAuthMethod.SHARED_TOKEN) {
            throw new IllegalArgumentException("Token is required");
        }
    }

    private void saveCompanySecret(Connector c, String secret) {
        if (c.authMethod() == ConnectorAuthMethod.SHARED_TOKEN && blank(secret) != null) {
            credentials.save(new ConnectorCredential(c.id(), ConnectorCredential.COMPANY, cipher.encrypt(secret.strip()),
                    null, Instant.now()));
        }
    }

    Connector require(String id) {
        if (BUILTIN_ID.equals(id)) {
            throw new ForbiddenException("SuperAgent's own connector is built in and can't be changed");
        }
        if (FEISHU_ID.equals(id)) {
            throw new ForbiddenException("Feishu is set up under System Settings → integrations");
        }
        return store.findConnector(id).orElseThrow(() -> new ConnectorNotFoundException(id));
    }

    // --- seeding ---

    /**
     * A fresh install offers GitHub; every install gets Gitee once (its official remote MCP server). Both are
     * authorized by each employee with their own account. A built-in an administrator deleted stays deleted.
     */
    private void seedDefaults() {
        Instant now = Instant.now();
        if (store.listConnectors().isEmpty()) {
            store.save(new Connector(ConnectorOAuthService.GITHUB, "GitHub",
                    "Repositories, issues and pull requests via the official GitHub MCP server.", ConnectorKind.MCP,
                    ConnectorAuthMethod.OAUTH, true, ConnectorTransport.STDIO, "npx -y @modelcontextprotocol/server-github",
                    "GITHUB_PERSONAL_ACCESS_TOKEN", now, now));
            log.info("seeded the GitHub connector");
        }
        String giteeSeeded = "connector.seeded." + ConnectorOAuthService.GITEE;
        if (!"true".equals(settings.all().get(giteeSeeded))) {
            if (store.findConnector(ConnectorOAuthService.GITEE).isEmpty()) {
                store.save(new Connector(ConnectorOAuthService.GITEE, "Gitee（码云）",
                        "仓库、Issue、Pull Request、发行版，通过 Gitee 官方的远程 MCP 服务。", ConnectorKind.MCP,
                        ConnectorAuthMethod.OAUTH, true, ConnectorTransport.HTTP, GITEE_MCP_URL, null, now, now));
                log.info("seeded the Gitee connector");
            }
            settings.put(giteeSeeded, "true");
        }
    }

    /** Gitee's hosted MCP server; it takes the employee's token as {@code Authorization: Bearer}. */
    static final String GITEE_MCP_URL = "https://api.gitee.com/mcp";

    // --- mapping ---

    private ConnectorDto toDto(Connector c, String viewer, boolean admin) {
        boolean needs;
        boolean authorized;
        String account = null;
        int authorizedCount = 0;
        if (c.authMethod().personal()) {
            needs = true;
            Optional<ConnectorCredential> mine = credentials.find(c.id(), viewer);
            authorized = mine.isPresent();
            account = mine.map(ConnectorCredential::accountLabel).orElse(null);
            if (admin) {
                authorizedCount = (int) credentials.list(c.id()).stream()
                        .filter(x -> !ConnectorCredential.COMPANY.equals(x.owner())).count();
            }
        } else {
            needs = false;
            authorized = c.authMethod() == ConnectorAuthMethod.NONE
                    || credentials.find(c.id(), ConnectorCredential.COMPANY).isPresent();
        }
        boolean secretSet = admin && credentials.find(c.id(), ConnectorCredential.COMPANY).isPresent();
        return new ConnectorDto(c.id(), c.name(), c.description(), lower(c.kind()), lower(c.authMethod()), c.enabled(),
                lower(c.transport()), null, null, needs, authorized, account, null,
                c.authMethod() == ConnectorAuthMethod.OAUTH && oauth.isConfigured(oauth.providerFor(c.id())),
                admin ? c.endpoint() : null, admin ? c.tokenEnv() : null, secretSet,
                authorizedCount, c.createdAt(), c.updatedAt());
    }

    // --- helpers ---

    /** 8 characters from an unambiguous alphabet (no 0/O, 1/I/L); the bot stops listening to a sender after a few misses. */
    private String bindCode() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static String cleanName(String name, String fallback) {
        String clean = name == null ? "" : name.strip();
        return clean.isEmpty() ? fallback : clean;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
