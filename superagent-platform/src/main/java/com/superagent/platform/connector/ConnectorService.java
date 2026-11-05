package com.superagent.platform.connector;

import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorAuthMethod;
import com.superagent.core.connector.ConnectorStatus;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.ConnectorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manages the MCP connector registry: CRUD plus connect/disconnect, which at this
 * stage only flips the stored {@link ConnectorStatus} (no live MCP/OAuth handshake
 * yet). On first start an empty store is seeded with common MCP servers so the
 * admin has a catalogue to connect rather than a blank page.
 *
 * <p>Depends only on the {@link ConnectorStore} SPI from {@code superagent-core};
 * the concrete backend is contributed by {@code superagent-storage} at runtime.
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);

    private final ConnectorStore store;
    private final ConnectorOAuthService oauth;

    public ConnectorService(ConnectorStore store, ConnectorOAuthService oauth) {
        this.store = store;
        this.oauth = oauth;
        seedDefaults();
    }

    public List<ConnectorDto> list() {
        return store.listConnectors().stream().map(this::toDto).toList();
    }

    public ConnectorDto create(SaveConnectorRequest request) {
        Instant now = Instant.now();
        Connector connector = new Connector(
                UUID.randomUUID().toString(),
                cleanName(request.name()),
                blankToNull(request.description()),
                parseType(request.type()),
                parseAuthMethod(request.authMethod()),
                ConnectorStatus.NOT_CONNECTED,
                parseTransport(request.transport()),
                blankToNull(request.endpoint()),
                now, now);
        store.save(connector);
        log.info("created connector {} ({})", connector.id(), connector.name());
        return toDto(connector);
    }

    public ConnectorDto update(String id, SaveConnectorRequest request) {
        Connector existing = require(id);
        Connector updated = new Connector(
                existing.id(),
                cleanName(request.name()),
                blankToNull(request.description()),
                parseType(request.type()),
                parseAuthMethod(request.authMethod()),
                existing.status(),
                parseTransport(request.transport()),
                blankToNull(request.endpoint()),
                existing.createdAt(), Instant.now());
        store.save(updated);
        return toDto(updated);
    }

    public void delete(String id) {
        require(id);
        store.delete(id);
    }

    public ConnectorDto connect(String id) {
        return setStatus(id, ConnectorStatus.CONNECTED);
    }

    public ConnectorDto disconnect(String id) {
        oauth.clearCredential(id);
        return setStatus(id, ConnectorStatus.NOT_CONNECTED);
    }

    private ConnectorDto setStatus(String id, ConnectorStatus status) {
        Connector updated = require(id).withStatus(status);
        store.save(updated);
        return toDto(updated);
    }

    private Connector require(String id) {
        return store.findConnector(id).orElseThrow(() -> new ConnectorNotFoundException(id));
    }

    // --- seeding ---

    private void seedDefaults() {
        if (!store.listConnectors().isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        int n = 0;
        for (Seed s : DEFAULT_CONNECTORS) {
            store.save(new Connector(s.id, s.name, s.description, ConnectorType.OPTIONAL,
                    s.authMethod, ConnectorStatus.NOT_CONNECTED, s.transport, s.endpoint, now, now));
            n++;
        }
        log.info("seeded {} default MCP connectors", n);
    }

    private record Seed(String id, String name, String description, ConnectorAuthMethod authMethod,
                        ConnectorTransport transport, String endpoint) {
    }

    /** Industry-common MCP servers offered as a starter catalogue; endpoints are the canonical launch commands/URLs. */
    private static final List<Seed> DEFAULT_CONNECTORS = List.of(
            new Seed("github", "GitHub", "Repositories, issues and pull requests via the official GitHub MCP server.",
                    ConnectorAuthMethod.SYSTEM_OAUTH, ConnectorTransport.STDIO,
                    "npx -y @modelcontextprotocol/server-github"));

    // --- parsing / mapping ---

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        return clean.isEmpty() ? "Untitled connector" : clean;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ConnectorType parseType(String value) {
        return value == null || value.isBlank()
                ? ConnectorType.OPTIONAL : ConnectorType.valueOf(value.trim().toUpperCase());
    }

    private static ConnectorAuthMethod parseAuthMethod(String value) {
        return value == null || value.isBlank()
                ? ConnectorAuthMethod.SYSTEM_OAUTH : ConnectorAuthMethod.valueOf(value.trim().toUpperCase());
    }

    private static ConnectorTransport parseTransport(String value) {
        return value == null || value.isBlank()
                ? ConnectorTransport.STDIO : ConnectorTransport.valueOf(value.trim().toUpperCase());
    }

    private ConnectorDto toDto(Connector c) {
        boolean connected = c.status() == ConnectorStatus.CONNECTED;
        String account = connected ? oauth.accountFor(c.id()) : null;
        String provider = oauth.providerFor(c.id());
        boolean oauthConfigured = provider != null && oauth.isConfigured();
        return new ConnectorDto(c.id(), c.name(), c.description(),
                c.type().name().toLowerCase(), c.authMethod().name().toLowerCase(),
                c.status().name().toLowerCase(), c.transport().name().toLowerCase(),
                c.endpoint(), provider, oauthConfigured, account,
                c.createdAt(), c.updatedAt());
    }
}
