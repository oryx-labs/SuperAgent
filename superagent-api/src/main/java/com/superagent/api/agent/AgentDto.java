package com.superagent.api.agent;

import java.time.Instant;

/**
 * An Agent (persona) for clients. {@code sessionId} is the agent's owning chat
 * session (phase 1 is 1:1); clients open it to chat with the agent.
 */
public record AgentDto(
        String id,
        String name,
        String persona,
        String model,
        String status,
        String sessionId,
        Instant createdAt,
        Instant updatedAt) {
}
