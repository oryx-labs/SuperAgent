package com.superagent.core.connector;

import java.time.Instant;

/**
 * A secret credential obtained for a connector (e.g. an OAuth access token),
 * kept out of {@link Connector} so it never rides along in API responses or logs.
 * {@code accountLabel} is the human-safe identity to display (e.g. a GitHub login).
 */
public record ConnectorCredential(
        String connectorId,
        String accessToken,
        String accountLabel,
        Instant updatedAt) {
}
