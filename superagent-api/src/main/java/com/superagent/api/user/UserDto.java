package com.superagent.api.user;

import java.time.Instant;
import java.util.List;

/** A provisioned user for admin views; the password hash is never exposed. */
public record UserDto(
        String id,
        String username,
        String displayName,
        List<String> roles,
        boolean enabled,
        String phone,
        String externalId,
        String source,
        Instant createdAt) {
}
