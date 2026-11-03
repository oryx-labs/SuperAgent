package com.superagent.api.chat;

import java.time.Instant;

/** A chat session for clients; internal fields (workspace path, agent session id) are omitted. */
public record SessionDto(
        String id,
        String title,
        String agentType,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
