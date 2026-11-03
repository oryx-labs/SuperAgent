package com.superagent.core.connector;

import java.time.Instant;

/**
 * An MCP server the agent can connect to, held as a registry entry. {@code endpoint}
 * is the stdio command for {@link ConnectorTransport#STDIO}, else the server URL.
 */
public record Connector(
        String id,
        String name,
        String description,
        ConnectorType type,
        ConnectorAuthMethod authMethod,
        ConnectorStatus status,
        ConnectorTransport transport,
        String endpoint,
        Instant createdAt,
        Instant updatedAt) {

    public Connector withStatus(ConnectorStatus newStatus) {
        return new Connector(id, name, description, type, authMethod, newStatus,
                transport, endpoint, createdAt, Instant.now());
    }
}
