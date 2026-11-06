package com.superagent.core.connector;

import java.time.Instant;

/**
 * An MCP server added by an administrator for agents to use. {@code endpoint} is the stdio command line (split on
 * whitespace) or the server URL; {@code tokenEnv} names the environment variable a stdio server reads its token from.
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
        Instant createdAt,
        Instant updatedAt) {
}
