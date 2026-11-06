package com.superagent.core.connector;

import java.time.Instant;

/**
 * A link from the company to an outside system, added by an administrator. For {@link ConnectorKind#MCP},
 * {@code endpoint} is the stdio command line (split on whitespace) or the server URL, and {@code tokenEnv}
 * names the environment variable a stdio server reads its token from. For {@link ConnectorKind#FEISHU},
 * {@code appId} is the company app; its secret lives in the credential store.
 *
 * <p>{@code status} / {@code lastError} report a live connection (Feishu's long connection): connecting,
 * connected or error; null for connectors that have none.
 */
public record Connector(
        String id,
        String name,
        String description,
        ConnectorKind kind,
        ConnectorAuthMethod authMethod,
        boolean enabled,
        ConnectorTransport transport,
        String endpoint,
        String tokenEnv,
        String appId,
        String status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public Connector withStatus(String newStatus, String newError) {
        return new Connector(id, name, description, kind, authMethod, enabled, transport, endpoint, tokenEnv, appId,
                newStatus, newError, createdAt, Instant.now());
    }
}
