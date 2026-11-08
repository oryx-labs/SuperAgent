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
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.platform.integration.IntegrationService;
import com.superagent.runtime.mcp.McpGrants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
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
    public static final String DINGTALK_ID = "dingtalk";

    private final ConnectorStore store;
    private final ConnectorCredentialStore credentials;
    private final EventSourceStore links;
    private final ConnectorOAuthService oauth;
    private final SecretCipher cipher;
    private final McpGrants grants;
    private final IntegrationService integrations;
    private final com.superagent.core.settings.SystemSettingStore settings;
    private final ConfluenceSite confluence;
    private final SecureRandom random = new SecureRandom();

    public ConnectorService(ConnectorStore store, ConnectorCredentialStore credentials, EventSourceStore links,
                            ConnectorOAuthService oauth, SecretCipher cipher,
                            McpGrants grants, IntegrationService integrations,
                            com.superagent.core.settings.SystemSettingStore settings, ConfluenceSite confluence) {
        this.store = store;
        this.credentials = credentials;
        this.links = links;
        this.oauth = oauth;
        this.cipher = cipher;
        this.grants = grants;
        this.integrations = integrations;
        this.settings = settings;
        this.confluence = confluence;
    }

    public List<ConnectorDto> list(String viewer, boolean admin) {
        List<ConnectorDto> out = new ArrayList<>();
        out.add(builtin(admin));
        if (integrations.ready(IntegrationService.FEISHU)) {
            out.add(feishuRow(viewer, admin));
        }
        if (integrations.ready(IntegrationService.DINGTALK)) {
            out.add(messagingRow(DINGTALK_ID, "钉钉", "dingtalk", IntegrationService.DINGTALK, "clientId", EventSource.DINGTALK,
                    viewer, admin));
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
                false, 0, null, st.checkedAt(), true, true, null, false);
    }

    /** Feishu as an employee sees it: whether they're linked, and the code to link if matching by phone didn't. */
    private ConnectorDto feishuRow(String viewer, boolean admin) {
        return messagingRow(FEISHU_ID, "飞书", "feishu", IntegrationService.FEISHU, "appId", EventSource.FEISHU, viewer, admin);
    }

    /** A messaging app (Feishu, DingTalk) as an employee sees it: linked or not, and their bind code. */
    private ConnectorDto messagingRow(String id, String name, String kind, String integration, String appField,
                                      String sourceType, String viewer, boolean admin) {
        String appId = integrations.value(integration, appField).orElse(null);
        EventSource link = links.find(viewer, sourceType).orElse(null);
        boolean linked = link != null && link.linkedTo(appId);
        int count = admin ? (int) links.list().stream()
                .filter(s -> sourceType.equals(s.type()) && s.linkedTo(appId)).count() : 0;
        return new ConnectorDto(id, name, null, kind, "none", true, "http",
                integrations.status(integration), admin ? integrations.lastError(integration) : null,
                true, linked, null, linked || link == null ? null : link.bindCode(), false, null, null, false, count,
                null, null, true, true, null, false);
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
        requireOwn(id);
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
        requireOwn(id);
        store.delete(id);
        credentials.deleteAll(id);
    }

    // --- an employee's own authorization ---

    /** Saves the viewer's personal token for a connector that acts with each employee's own identity. */
    public ConnectorDto authorize(String id, String viewer, String token, boolean admin) {
        return authorize(id, viewer, token, null, admin);
    }

    /**
     * {@code account}: the name that goes with the token where the service needs one (Confluence Cloud: the email).
     * A Confluence token is checked against the site first, so a wrong one is caught here rather than in a run.
     */
    public ConnectorDto authorize(String id, String viewer, String token, String account, boolean admin) {
        Connector c = require(id);
        if (c.authMethod() != ConnectorAuthMethod.PERSONAL_TOKEN) {
            throw new IllegalArgumentException("This connector doesn't take a personal token");
        }
        if (blank(token) == null) {
            throw new IllegalArgumentException("Token is required");
        }
        String label = null;
        if (CONFLUENCE_ID.equals(id)) {
            String site = confluenceSite().orElseThrow(() ->
                    new IllegalArgumentException("管理员还没在系统设置里填 Confluence 的地址"));
            label = confluence.verify(site, blank(account), token.strip());
        }
        credentials.save(new ConnectorCredential(id, viewer, cipher.encrypt(token.strip()), label, Instant.now()));
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
        boolean dingtalk = DINGTALK_ID.equals(id);
        String integration = dingtalk ? IntegrationService.DINGTALK : IntegrationService.FEISHU;
        if (!(FEISHU_ID.equals(id) || dingtalk) || !integrations.ready(integration)) {
            throw new IllegalArgumentException("Binding is only for Feishu or DingTalk, once enabled in System Settings");
        }
        String type = dingtalk ? EventSource.DINGTALK : EventSource.FEISHU;
        String code = bindCode();
        links.save(links.find(viewer, type).map(s -> s.withBindCode(code))
                .orElseGet(() -> EventSource.create(viewer, type, null, null, code, DIGEST_TIME,
                        ZONE.getId(), LocalDate.now(ZONE))));
        return dingtalk ? messagingRow(DINGTALK_ID, "钉钉", "dingtalk", IntegrationService.DINGTALK, "clientId",
                EventSource.DINGTALK, viewer, admin) : feishuRow(viewer, admin);
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
            if (!OWN_TOOLS.contains(c.id())) {
                specFor(c, owner).ifPresent(out::add);
            }
        }
        return out;
    }

    /** The MCP server {@code owner}'s agents would get for this connector; empty while it isn't usable for them. */
    public Optional<McpServerSpec> specFor(String id, String owner) {
        return store.findConnector(id).flatMap(c -> specFor(c, owner));
    }

    private Optional<McpServerSpec> specFor(Connector c, String owner) {
        if (c.kind() != ConnectorKind.MCP || !c.enabled() || c.endpoint() == null) {
            return Optional.empty();
        }
        if (DINGTALK_TOOLS_ID.equals(c.id()) && !integrations.ready(IntegrationService.DINGTALK)) {
            return Optional.empty(); // runs on the company's DingTalk app: nothing to run it with until that's set up
        }
        if (c.authMethod() == ConnectorAuthMethod.OAUTH && !oauth.isConfigured(oauth.providerFor(c.id()))) {
            return Optional.empty(); // its app is switched off under System Settings
        }
        String token = null;
        if (c.authMethod() != ConnectorAuthMethod.NONE) {
            String who = c.authMethod().personal() ? owner : ConnectorCredential.COMPANY;
            Optional<ConnectorCredential> cred = credentials.find(c.id(), who);
            if (cred.isEmpty()) {
                return Optional.empty();
            }
            // OAuth tokens may expire (Gitee's after a day): the OAuth service renews them first.
            Optional<String> usable = c.authMethod() == ConnectorAuthMethod.OAUTH ? oauth.accessToken(cred.get())
                    : decrypt(c, cred.get());
            if (usable.isEmpty()) {
                return Optional.empty();
            }
            token = usable.get();
        }
        McpServerSpec spec = spec(c, token);
        if (CONFLUENCE_ID.equals(c.id())) {
            Optional<String> site = confluenceSite();
            if (site.isEmpty()) {
                return Optional.empty(); // the site isn't set under System Settings
            }
            String account = credentials.find(c.id(), owner).map(ConnectorCredential::accountLabel).orElse(null);
            spec = new McpServerSpec(spec.name(), spec.transport(), spec.url(), spec.headers(), spec.command(), spec.args(),
                    ConfluenceSite.env(site.get(), account, token));
        }
        if (DINGTALK_TOOLS_ID.equals(c.id())) {
            Map<String, String> env = new LinkedHashMap<>(spec.env());
            env.put("DINGTALK_Client_ID", integrations.value(IntegrationService.DINGTALK, "clientId").orElse(""));
            env.put("DINGTALK_Client_Secret", integrations.value(IntegrationService.DINGTALK, "clientSecret").orElse(""));
            env.put("ACTIVE_PROFILES", DINGTALK_PROFILES);
            spec = new McpServerSpec(spec.name(), spec.transport(), spec.url(), spec.headers(), spec.command(), spec.args(), env);
        }
        return Optional.of(spec);
    }

    /** Whether the viewer has authorized a connector that needs their own authorization. */
    public boolean authorizedBy(String id, String viewer) {
        return credentials.find(id, viewer).isPresent();
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
    @EventListener(CompanyStarted.class)
    void seedDefaults() {
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
        seedOnce(FEISHU_DOCS_ID, new Connector(FEISHU_DOCS_ID, "飞书 / Lark 文档",
                "以员工本人身份搜索、阅读、新建和修改飞书 / Lark 云文档（新版文档）。用公司的飞书应用登录，无需另建应用。",
                ConnectorKind.MCP, ConnectorAuthMethod.OAUTH, true, ConnectorTransport.HTTP, null, null, now, now));
        seedOnce(NOTION_ID, new Connector(NOTION_ID, "Notion",
                "员工连接自己的 Notion 工作区（授权时选择共享哪些页面），通过 Notion 官方 MCP 服务读写页面和数据库。",
                ConnectorKind.MCP, ConnectorAuthMethod.OAUTH, true, ConnectorTransport.STDIO,
                "npx -y @notionhq/notion-mcp-server", "NOTION_TOKEN", now, now));
        seedOnce(CONFLUENCE_ID, new Connector(CONFLUENCE_ID, "Confluence",
                "以员工本人身份搜索、阅读、新建和修改 Confluence 页面（公司自建版和 Cloud 都支持）。管理员在系统设置里填站点地址，员工用自己的令牌授权。",
                ConnectorKind.MCP, ConnectorAuthMethod.PERSONAL_TOKEN, true, ConnectorTransport.STDIO, CONFLUENCE_COMMAND,
                "CONFLUENCE_PERSONAL_TOKEN", now, now));
        // App identity, not each employee's: off until an administrator turns it on knowingly.
        seedOnce(DINGTALK_TOOLS_ID, new Connector(DINGTALK_TOOLS_ID, "钉钉工具",
                "钉钉官方 MCP：通讯录、日程、待办、日志。以公司钉钉应用的身份调用，所有员工的 Agent 共用这个身份，开启前请确认应用权限。",
                ConnectorKind.MCP, ConnectorAuthMethod.NONE, false, ConnectorTransport.STDIO, DINGTALK_TOOLS_COMMAND, null, now, now));
    }

    /** The connectors the system ships: an administrator can turn them on or off, nothing else. */
    static final java.util.Set<String> SYSTEM_IDS = java.util.Set.of(ConnectorOAuthService.GITHUB,
            ConnectorOAuthService.GITEE, FeishuDocsOAuthClient.KEY, NotionOAuthClient.KEY, "dingtalk-tools", "confluence");

    static boolean isSystem(String id) {
        return SYSTEM_IDS.contains(id);
    }

    private static void requireOwn(String id) {
        if (isSystem(id)) {
            throw new com.superagent.core.error.ForbiddenException("系统自带的连接不能编辑或删除，只能启用或停用");
        }
    }

    /** Turns a connector on or off: the one thing that can be changed about a system connector. */
    public ConnectorDto setEnabled(String id, boolean enabled, String viewer) {
        Connector c = require(id);
        Connector next = new Connector(c.id(), c.name(), c.description(), c.kind(), c.authMethod(), enabled, c.transport(),
                c.endpoint(), c.tokenEnv(), c.createdAt(), Instant.now());
        store.save(next);
        log.info("connector {} {}", id, enabled ? "enabled" : "disabled");
        return toDto(next, viewer, true);
    }

    /** Adds a built-in connector the first time this install sees it; one an administrator deleted stays deleted. */
    private void seedOnce(String id, Connector connector) {
        String flag = "connector.seeded." + id;
        if (!"true".equals(settings.all().get(flag))) {
            if (store.findConnector(id).isEmpty()) {
                store.save(connector);
                log.info("seeded the {} connector", id);
            }
            settings.put(flag, "true");
        }
    }

    public static final String FEISHU_DOCS_ID = FeishuDocsOAuthClient.KEY;
    public static final String NOTION_ID = NotionOAuthClient.KEY;
    public static final String DINGTALK_TOOLS_ID = "dingtalk-tools";
    public static final String CONFLUENCE_ID = "confluence";
    /** mcp-atlassian (Confluence and Jira, Cloud and self-hosted), pinned: run by uv, which fetches it on first use. */
    static final String CONFLUENCE_COMMAND = "uvx mcp-atlassian@0.23.1";
    static final String DINGTALK_TOOLS_COMMAND = "npx -y dingtalk-mcp@latest";
    /** DingTalk's official MCP server: which of its tool groups agents get. */
    static final String DINGTALK_PROFILES = "dingtalk-contacts,dingtalk-department,dingtalk-calendar,dingtalk-tasks,dingtalk-report";

    /**
     * Connectors whose tools SuperAgent serves itself (an employee's authorization is all they hold), so they are
     * never started as an MCP server of their own.
     */
    private static final java.util.Set<String> OWN_TOOLS = java.util.Set.of(FEISHU_DOCS_ID);

    /** The employee's usable token for a connector they authorized themselves (renewed first if it's about to expire). */
    public Optional<String> userToken(String connectorId, String owner) {
        return store.findConnector(connectorId).filter(Connector::enabled)
                .flatMap(c -> credentials.find(connectorId, owner))
                .flatMap(oauth::accessToken);
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
                authorizedCount, c.createdAt(), c.updatedAt(), isSystem(c.id()), setUp(c),
                CONFLUENCE_ID.equals(c.id()) ? confluenceSite().orElse(null) : null,
                CONFLUENCE_ID.equals(c.id()) && confluenceSite().map(ConfluenceSite::isCloud).orElse(false));
    }

    /** Whether employees can authorize yet: the OAuth app or the Confluence site is set up under System Settings. */
    private boolean setUp(Connector c) {
        if (c.authMethod() == ConnectorAuthMethod.OAUTH) {
            return oauth.isConfigured(oauth.providerFor(c.id()));
        }
        return !CONFLUENCE_ID.equals(c.id()) || confluenceSite().isPresent();
    }

    private Optional<String> confluenceSite() {
        return integrations.value(IntegrationService.CONFLUENCE, "url");
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
