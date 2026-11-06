package com.superagent.platform.connector;

import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorAuthMethod;
import com.superagent.core.connector.ConnectorChanged;
import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorKind;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.core.error.ConflictException;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * Connectors: how the company links to outside systems. An administrator adds them; employees authorize
 * the ones that act with a personal identity. MCP connectors give agents tools ({@link #mcpServersFor});
 * the Feishu connector brings messages to each employee's SuperAgent (run by the event module, which
 * follows {@link ConnectorChanged}). Secrets are encrypted and never leave this service.
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DIGEST_TIME = "09:00";

    private final ConnectorStore store;
    private final ConnectorCredentialStore credentials;
    private final EventSourceStore links;
    private final ConnectorOAuthService oauth;
    private final SecretCipher cipher;
    private final ApplicationEventPublisher publisher;
    private final SecureRandom random = new SecureRandom();

    public ConnectorService(ConnectorStore store, ConnectorCredentialStore credentials, EventSourceStore links,
                            ConnectorOAuthService oauth, SecretCipher cipher, ApplicationEventPublisher publisher) {
        this.store = store;
        this.credentials = credentials;
        this.links = links;
        this.oauth = oauth;
        this.cipher = cipher;
        this.publisher = publisher;
        seedDefaults();
    }

    public List<ConnectorDto> list(String viewer, boolean admin) {
        return store.listConnectors().stream().map(c -> toDto(c, viewer, admin)).toList();
    }

    // --- administration ---

    public ConnectorDto create(SaveConnectorRequest request, String viewer) {
        ConnectorKind kind = parse(ConnectorKind.class, request.kind(), ConnectorKind.MCP);
        if (kind == ConnectorKind.FEISHU && feishu().isPresent()) {
            throw new ConflictException("The company already has a Feishu connector");
        }
        Instant now = Instant.now();
        Connector connector = validated(new Connector(UUID.randomUUID().toString(), null, null, kind, null,
                true, null, null, null, null, null, null, now, now), request);
        requireSecret(connector, request);
        store.save(connector);
        saveCompanySecret(connector, request.secret());
        log.info("created {} connector {} ({})", kind, connector.id(), connector.name());
        publisher.publishEvent(new ConnectorChanged(connector.id()));
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
        publisher.publishEvent(new ConnectorChanged(id));
        return toDto(updated, viewer, true);
    }

    public void delete(String id) {
        require(id);
        store.delete(id);
        credentials.deleteAll(id);
        publisher.publishEvent(new ConnectorChanged(id));
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
        Connector c = require(id);
        if (c.kind() != ConnectorKind.FEISHU || !c.enabled()) {
            throw new IllegalArgumentException("Binding is only for an enabled Feishu connector");
        }
        String code = bindCode();
        links.save(links.find(viewer, EventSource.FEISHU).map(s -> s.withBindCode(code))
                .orElseGet(() -> EventSource.create(viewer, EventSource.FEISHU, null, null, code, DIGEST_TIME,
                        ZONE.getId(), LocalDate.now(ZONE))));
        return toDto(c, viewer, admin);
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
            String token = null;
            if (c.authMethod() != ConnectorAuthMethod.NONE) {
                String who = c.authMethod().personal() ? owner : ConnectorCredential.COMPANY;
                Optional<ConnectorCredential> cred = credentials.find(c.id(), who);
                if (cred.isEmpty()) {
                    continue;
                }
                try {
                    token = cipher.decrypt(cred.get().secretCipher());
                } catch (IllegalStateException e) {
                    log.warn("connector {}: stored token can't be decrypted, skipping it", c.id());
                    continue;
                }
            }
            out.add(spec(c, token));
        }
        return out;
    }

    /** The tool allowlist entries that let an agent call every tool of these servers. */
    public static List<String> toolPatterns(List<McpServerSpec> servers) {
        return servers.stream().map(s -> "mcp__" + s.name()).toList();
    }

    /** The company's Feishu connector, if one has been added. */
    public Optional<Connector> feishu() {
        return store.listConnectors().stream().filter(c -> c.kind() == ConnectorKind.FEISHU).findFirst();
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
        if (base.kind() == ConnectorKind.FEISHU) {
            String appId = blank(r.appId());
            if (appId == null) {
                throw new IllegalArgumentException("App ID is required");
            }
            return new Connector(base.id(), cleanName(r.name(), "飞书"), blank(r.description()), ConnectorKind.FEISHU,
                    ConnectorAuthMethod.NONE, enabled, ConnectorTransport.STDIO, null, null, appId,
                    appId.equals(base.appId()) ? base.status() : null, appId.equals(base.appId()) ? base.lastError() : null,
                    base.createdAt(), now);
        }
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
                enabled, transport, endpoint, tokenEnv, null, null, null, base.createdAt(), now);
    }

    private void requireSecret(Connector c, SaveConnectorRequest r) {
        if (blank(r.secret()) != null) {
            return;
        }
        if (c.kind() == ConnectorKind.FEISHU) {
            throw new IllegalArgumentException("App Secret is required");
        }
        if (c.authMethod() == ConnectorAuthMethod.SHARED_TOKEN) {
            throw new IllegalArgumentException("Token is required");
        }
    }

    private void saveCompanySecret(Connector c, String secret) {
        boolean takesOne = c.kind() == ConnectorKind.FEISHU || c.authMethod() == ConnectorAuthMethod.SHARED_TOKEN;
        if (takesOne && blank(secret) != null) {
            credentials.save(new ConnectorCredential(c.id(), ConnectorCredential.COMPANY, cipher.encrypt(secret.strip()),
                    null, Instant.now()));
        }
    }

    Connector require(String id) {
        return store.findConnector(id).orElseThrow(() -> new ConnectorNotFoundException(id));
    }

    // --- seeding ---

    /** A fresh install offers GitHub, authorized by each employee with their own account. */
    private void seedDefaults() {
        if (!store.listConnectors().isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        store.save(new Connector(ConnectorOAuthService.GITHUB, "GitHub",
                "Repositories, issues and pull requests via the official GitHub MCP server.", ConnectorKind.MCP,
                ConnectorAuthMethod.OAUTH, true, ConnectorTransport.STDIO, "npx -y @modelcontextprotocol/server-github",
                "GITHUB_PERSONAL_ACCESS_TOKEN", null, null, null, now, now));
        log.info("seeded the GitHub connector");
    }

    // --- mapping ---

    private ConnectorDto toDto(Connector c, String viewer, boolean admin) {
        boolean needs;
        boolean authorized;
        String account = null;
        String bindCode = null;
        int authorizedCount = 0;
        if (c.kind() == ConnectorKind.FEISHU) {
            needs = true;
            EventSource link = links.find(viewer, EventSource.FEISHU).orElse(null);
            authorized = link != null && link.linkedTo(c.appId());
            bindCode = !authorized && link != null ? link.bindCode() : null;
            if (admin) {
                authorizedCount = (int) links.list().stream()
                        .filter(s -> EventSource.FEISHU.equals(s.type()) && s.linkedTo(c.appId())).count();
            }
        } else if (c.authMethod().personal()) {
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
                lower(c.transport()), c.status(), admin ? c.lastError() : null, needs, authorized, account, bindCode,
                c.authMethod() == ConnectorAuthMethod.OAUTH && oauth.isConfigured(),
                admin ? c.endpoint() : null, admin ? c.tokenEnv() : null, admin ? c.appId() : null, secretSet,
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
