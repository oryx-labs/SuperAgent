package com.superagent.api.provider;

import java.time.Instant;

/**
 * One entry of a CLI's key pool as the Models page shows it. The key itself never leaves the server:
 * {@code keyHint} is a masked form. {@code env} marks the read-only last entry standing for the server's own
 * environment / CLI login; {@code current} the entry runs use right now.
 */
public record CredentialDto(
        String id,
        String label,
        String baseUrl,
        String keyHint,
        boolean bearer,
        int priority,
        boolean enabled,
        String status,
        String lastError,
        Instant checkedAt,
        boolean current,
        boolean env,
        /** console (added on the page) or machine (found on the server and imported). */
        String source) {
}
