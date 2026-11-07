package com.superagent.api.skill;

import java.time.Instant;

/** A skill deletion proposed in a conversation, for its card; status pending | confirmed | cancelled. */
public record SkillDeletionDto(String id, String skillId, String skillName, String messageId, String status, Instant createdAt) {
}
