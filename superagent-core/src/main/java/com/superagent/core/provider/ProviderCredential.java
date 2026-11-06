package com.superagent.core.provider;

import java.time.Instant;

/**
 * One endpoint + key for a model CLI (e.g. the official API, or a relay), in a priority-ordered pool. Runs use
 * the highest-priority healthy entry; a failing one is marked {@link #ERROR} until a check sees it answer again.
 * The key is stored only encrypted ({@code keyCipher}).
 */
public record ProviderCredential(
        String id,
        String providerKey,
        String label,
        String baseUrl,
        String keyCipher,
        /** Send the key as a Bearer token (relays/gateways) instead of the vendor's API-key header. */
        boolean bearer,
        int priority,
        boolean enabled,
        String status,
        String lastError,
        Instant checkedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String UNKNOWN = "unknown";

    public boolean usable() {
        return enabled && !ERROR.equals(status);
    }

    public ProviderCredential withHealth(String newStatus, String error) {
        return new ProviderCredential(id, providerKey, label, baseUrl, keyCipher, bearer, priority, enabled, newStatus,
                error, Instant.now(), createdAt, Instant.now());
    }

    public ProviderCredential withPriority(int newPriority) {
        return new ProviderCredential(id, providerKey, label, baseUrl, keyCipher, bearer, newPriority, enabled, status,
                lastError, checkedAt, createdAt, Instant.now());
    }
}
