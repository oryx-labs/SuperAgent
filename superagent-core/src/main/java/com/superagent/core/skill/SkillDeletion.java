package com.superagent.core.skill;

import java.time.Instant;

/**
 * A skill deletion an agent proposed in a conversation ("delete the old style guide"), waiting for the person who
 * asked to confirm on its card: every agent using the skill loses it, so an agent never deletes one on its own.
 * {@code agentId} is the conversation's agent and {@code messageId} the reply that proposed it, where its card shows;
 * {@code requester} is who asked and the only one who can confirm.
 */
public record SkillDeletion(
        String id,
        String skillId,
        String skillName,
        String agentId,
        String requester,
        String sessionId,
        String messageId,
        Status status,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status { PENDING, CONFIRMED, CANCELLED }

    public SkillDeletion withStatus(Status next) {
        return new SkillDeletion(id, skillId, skillName, agentId, requester, sessionId, messageId, next, createdAt, Instant.now());
    }
}
