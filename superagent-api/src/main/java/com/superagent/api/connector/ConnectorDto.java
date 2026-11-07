package com.superagent.api.connector;

import java.time.Instant;

/**
 * A connector as the viewer sees it. Enum fields are lowercase strings. The "my…" fields are the viewer's own
 * authorization; the admin-only fields ({@code endpoint}, {@code tokenEnv}, {@code companySecretSet},
 * {@code authorizedCount}) are null / false / 0 for everyone else.
 */
public record ConnectorDto(
        String id,
        String name,
        String description,
        /** builtin (SuperAgent's own) | mcp | feishu (from System Settings → integrations) */
        String kind,
        /** none | shared_token | personal_token | oauth */
        String authMethod,
        boolean enabled,
        String transport,
        /** Live connection status (Feishu): connecting | connected | error; null when there is none. */
        String status,
        String lastError,
        /** True when the viewer must authorize before agents can use it on their behalf. */
        boolean needsAuthorization,
        /** The viewer has authorized (or nothing is needed). */
        boolean authorized,
        /** Human-safe identity of the viewer's account (a GitHub login, their Feishu name). */
        String account,
        /** Feishu: the code the viewer sends the company bot to link themselves, while not linked. */
        String bindCode,
        /** OAuth: whether the provider's app is set up under System Settings, so authorizing can proceed. */
        boolean oauthConfigured,
        String endpoint,
        String tokenEnv,
        /** A shared token / app secret is stored. */
        boolean companySecretSet,
        /** How many employees have authorized (or are linked, for Feishu). */
        int authorizedCount,
        Instant createdAt,
        Instant updatedAt,
        /**
         * Comes with the system (SuperAgent's own, the messaging apps, the built-in MCP connectors): it can't be edited
         * or deleted, only turned on and off. Only connectors an administrator added are theirs to change.
         */
        boolean system) {
}
