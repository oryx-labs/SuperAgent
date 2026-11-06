package com.superagent.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * An invitation an agent proposed in a conversation ("add Zhang San"), waiting for the agent's owner to confirm:
 * joining shows someone the whole conversation, so an agent never adds people on its own. {@code messageId} is
 * the reply that proposed it, where its card shows.
 */
public record AgentInvite(
        String id,
        String agentId,
        String owner,
        List<String> usernames,
        String sessionId,
        String messageId,
        Status status,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status { PENDING, CONFIRMED, CANCELLED }

    public AgentInvite {
        usernames = usernames == null ? List.of() : List.copyOf(usernames);
    }

    public AgentInvite withStatus(Status next) {
        return new AgentInvite(id, agentId, owner, usernames, sessionId, messageId, next, createdAt, Instant.now());
    }
}
