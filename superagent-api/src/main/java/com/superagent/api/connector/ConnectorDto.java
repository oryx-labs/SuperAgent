package com.superagent.api.connector;

import java.time.Instant;

/** An MCP connector registry entry for clients. Enum fields are lowercase strings. */
public record ConnectorDto(
        String id,
        String name,
        String description,
        String type,
        String authMethod,
        String status,
        String transport,
        String endpoint,
        /** Non-null when Connect runs a real OAuth handshake with this provider (e.g. "github"). */
        String oauthProvider,
        /** True when the OAuth provider's app credentials are configured, so Connect can actually proceed. */
        boolean oauthConfigured,
        /** Human-safe identity of the connected account (e.g. a GitHub login), when connected. */
        String account,
        Instant createdAt,
        Instant updatedAt) {
}
