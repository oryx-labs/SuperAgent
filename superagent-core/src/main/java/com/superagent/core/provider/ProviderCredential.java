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
        Instant updatedAt,
        /** {@link #CONSOLE} (added on the page) or {@link #MACHINE} (found in the server's environment / CLI config). */
        String source,
        /** SHA-256 of the key, to recognise the same key again (also among deleted entries) without decrypting. */
        String keyHash) {

    public static final String CONSOLE = "console";
    public static final String MACHINE = "machine";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String UNKNOWN = "unknown";

    public boolean usable() {
        return enabled && !ERROR.equals(status);
    }

    public ProviderCredential withHealth(String newStatus, String error) {
        return new ProviderCredential(id, providerKey, label, baseUrl, keyCipher, bearer, priority, enabled, newStatus,
                error, Instant.now(), createdAt, Instant.now(), source, keyHash);
    }

    public ProviderCredential withPriority(int newPriority) {
        return new ProviderCredential(id, providerKey, label, baseUrl, keyCipher, bearer, newPriority, enabled, status,
                lastError, checkedAt, createdAt, Instant.now(), source, keyHash);
    }

    public static String hash(String key) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
