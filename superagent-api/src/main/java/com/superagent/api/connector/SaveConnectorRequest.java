package com.superagent.api.connector;

/**
 * Create or update a connector (administrators). Enum fields are lowercase strings. {@code secret} is the
 * shared token (MCP, shared_token) or the app secret (Feishu); blank keeps the stored one.
 */
public record SaveConnectorRequest(
        String name,
        String description,
        /** mcp (default) | feishu */
        String kind,
        /** none (default) | shared_token | personal_token | oauth */
        String authMethod,
        /** stdio (default) | http | sse */
        String transport,
        String endpoint,
        String tokenEnv,
        String appId,
        String secret,
        Boolean enabled) {
}
