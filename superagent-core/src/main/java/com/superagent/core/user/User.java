package com.superagent.core.user;

import java.time.Instant;
import java.util.Set;

/**
 * A provisioned account that may sign in. The platform is <em>preprovision-only</em>:
 * a login (local / phone / SSO / WeChat) is accepted only if a matching enabled user
 * exists here, otherwise it is rejected.
 *
 * <p>{@code id} is who the person is: never changed or reused, and what everything they own records. {@code username}
 * is only how they sign in, unique within their company. {@code mustChangePassword}: a password someone else set
 * (generated at sign-up, or the built-in default), to be replaced at the next sign-in.
 *
 * <p>{@code username} is the login name. {@code passwordHash} is set for LOCAL users
 * (BCrypt), null otherwise. {@code phone} matches PHONE logins; {@code externalId}
 * matches an SSO subject / WeChat id. {@code roles} are bare role names (e.g. ADMIN).
 */
public record User(
        String id,
        String username,
        String displayName,
        Set<String> roles,
        boolean enabled,
        String passwordHash,
        String phone,
        String externalId,
        UserSource source,
        Instant createdAt,
        Instant updatedAt,
        boolean mustChangePassword) {

    public User(String id, String username, String displayName, Set<String> roles, boolean enabled, String passwordHash,
                String phone, String externalId, UserSource source, Instant createdAt, Instant updatedAt) {
        this(id, username, displayName, roles, enabled, passwordHash, phone, externalId, source, createdAt, updatedAt, false);
    }

    public boolean isAdmin() {
        return roles != null && roles.contains("ADMIN");
    }

    public User withEnabled(boolean newEnabled) {
        return new User(id, username, displayName, roles, newEnabled, passwordHash, phone, externalId,
                source, createdAt, Instant.now(), mustChangePassword);
    }

    /** The user's own new password: nothing left to change. */
    public User withPasswordHash(String newHash) {
        return new User(id, username, displayName, roles, enabled, newHash, phone, externalId,
                source, createdAt, Instant.now(), false);
    }
}
