package com.superagent.api.connector;

/**
 * Create or update an MCP connector (administrators). Enum fields are lowercase strings. {@code secret} is the
 * company token for shared_token; blank keeps the stored one.
 */
public record SaveConnectorRequest(
        String name,
        String description,
        /** none (default) | shared_token | personal_token | oauth */
        String authMethod,
        /** stdio (default) | http | sse */
        String transport,
        String endpoint,
        String tokenEnv,
        String secret,
        Boolean enabled) {
}
