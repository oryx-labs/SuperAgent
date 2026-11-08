package com.superagent.core.tenant;

import java.time.Instant;

/**
 * A company using the system: everything else belongs to exactly one. {@code dataKeyCipher} is the company's own
 * data key, encrypted with the master key (envelope encryption); null until one is made. The limits override the
 * configured defaults when set (null = the default; 0 = unlimited).
 */
public record Company(
        String id,
        String name,
        boolean enabled,
        String dataKeyCipher,
        Integer maxUsers,
        Integer maxRunningTasks,
        Long monthlyTokens,
        Instant createdAt,
        Instant updatedAt) {

    /** The company a single-company install (and every install, until companies can be created) runs as. */
    public static final String DEFAULT_ID = "default";

    /** A company on the configured default limits. */
    public Company(String id, String name, boolean enabled, String dataKeyCipher, Instant createdAt, Instant updatedAt) {
        this(id, name, enabled, dataKeyCipher, null, null, null, createdAt, updatedAt);
    }

    public Company withDataKey(String cipher) {
        return new Company(id, name, enabled, cipher, maxUsers, maxRunningTasks, monthlyTokens, createdAt, updatedAt);
    }
}
