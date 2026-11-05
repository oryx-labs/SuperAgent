package com.superagent.core.agent;

import java.time.Instant;

/**
 * A collaborator on another user's agent: they see its whole conversation and direct it alongside
 * the owner. The owner is not a member row — {@link Agent#owner()} stays the single source of that.
 * {@code active=false} is a removal (kept as a row so both storage backends treat it as an upsert).
 */
public record AgentMember(String agentId, String username, String addedBy, boolean active, Instant createdAt,
                          Instant updatedAt) {
}
