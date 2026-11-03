package com.superagent.api.user;

import java.util.List;

/**
 * Create or update a provisioned user. {@code username} is required. {@code password}
 * sets/updates a LOCAL login (ignored for other sources; on update, blank leaves it
 * unchanged). {@code source} defaults to local, {@code roles} to [USER].
 */
public record SaveUserRequest(
        String username,
        String displayName,
        List<String> roles,
        Boolean enabled,
        String password,
        String phone,
        String externalId,
        String source) {
}
