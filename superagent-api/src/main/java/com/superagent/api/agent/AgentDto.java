package com.superagent.api.agent;

import java.time.Instant;

/**
 * An Agent (persona) for clients. {@code sessionId} is the agent's owning chat
 * session (phase 1 is 1:1); clients open it to chat with the agent.
 */
public record AgentDto(
        String id,
        String name,
        /** Emoji avatar (one of AgentService's built-ins), or null for agents created before avatars. */
        String avatar,
        String persona,
        String model,
        String status,
        String sessionId,
        Instant createdAt,
        Instant updatedAt) {
}
