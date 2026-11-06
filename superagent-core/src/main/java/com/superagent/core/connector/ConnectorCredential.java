package com.superagent.core.connector;

import java.time.Instant;

/**
 * A secret for a connector, kept out of {@link Connector} so it never rides along in API responses or logs.
 * {@code owner} is the employee it belongs to, or {@link #COMPANY} for the company's own (a shared token, the
 * Feishu app secret). {@code secretCipher} is encrypted with {@link com.superagent.core.security.SecretCipher}.
 * {@code accountLabel} is the human-safe identity to display (e.g. a GitHub login). Tokens that expire (Gitee's)
 * carry {@code refreshCipher}, encrypted the same way, and {@code expiresAt}; both are null for ones that don't.
 */
public record ConnectorCredential(
        String connectorId,
        String owner,
        String secretCipher,
        String accountLabel,
        Instant updatedAt,
        String refreshCipher,
        Instant expiresAt) {

    public static final String COMPANY = "";

    /** A token that doesn't expire. */
    public ConnectorCredential(String connectorId, String owner, String secretCipher, String accountLabel, Instant updatedAt) {
        this(connectorId, owner, secretCipher, accountLabel, updatedAt, null, null);
    }
}
