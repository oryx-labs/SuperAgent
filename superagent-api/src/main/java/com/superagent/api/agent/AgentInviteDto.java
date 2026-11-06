package com.superagent.api.agent;

import java.time.Instant;
import java.util.List;

/**
 * An invitation an agent proposed. {@code names}: the invitees' display names, in {@code usernames}' order;
 * {@code messageId}: the reply its card shows under; status pending | confirmed | cancelled.
 */
public record AgentInviteDto(String id, List<String> usernames, List<String> names, String messageId, String status,
                             Instant createdAt) {
}
