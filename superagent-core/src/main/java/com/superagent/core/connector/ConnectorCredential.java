package com.superagent.core.connector;

import java.time.Instant;

/**
 * A secret for a connector, kept out of {@link Connector} so it never rides along in API responses or logs.
 * {@code owner} is the employee it belongs to, or {@link #COMPANY} for the company's own (a shared token, the
 * Feishu app secret). {@code secretCipher} is encrypted with {@link com.superagent.core.security.SecretCipher}.
 * {@code accountLabel} is the human-safe identity to display (e.g. a GitHub login).
 */
public record ConnectorCredential(
        String connectorId,
        String owner,
        String secretCipher,
        String accountLabel,
        Instant updatedAt) {

    public static final String COMPANY = "";
}
