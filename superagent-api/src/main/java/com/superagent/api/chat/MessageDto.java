package com.superagent.api.chat;

import java.time.Instant;

/** A settled chat message returned to clients. */
public record MessageDto(
        String id,
        String role,
        String content,
        Instant createdAt) {
}
