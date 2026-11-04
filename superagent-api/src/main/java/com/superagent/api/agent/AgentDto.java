package com.superagent.api.agent;

import java.time.Instant;
import java.util.List;

/**
 * An Agent (persona) for clients. {@code sessionId} is the agent's owning chat
 * session (phase 1 is 1:1); clients open it to chat with the agent.
 */
public record AgentDto(
        String id,
        String name,
        /** Emoji avatar (one of AgentService's built-ins), or null for agents created before avatars. */
        String avatar,
        /** Built-in character key (see GET /agents/characters), or null for a custom character in {@code persona}. */
        String character,
        String persona,
        String model,
        /** Attached skill ids, in attach order. */
        List<String> skillIds,
        String status,
        /** standard | super — the user's built-in SuperAgent (pinned first, not deletable). */
        String kind,
        /** Id of the SuperAgent that hired this agent as a team member, or null if the user made it. */
        String managedBy,
        String sessionId,
        Instant createdAt,
        Instant updatedAt) {
}
