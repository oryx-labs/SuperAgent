package com.superagent.api.inbox;

import java.time.Instant;

/**
 * One of the user's data sources (v1: their Feishu bot) for clients. The app secret is never returned — only
 * whether one is set. {@code bound} says the owner sent the bot the bind code; {@code bindCode} is shown until
 * then. {@code today} / {@code lastMessageAt} describe what it has received.
 */
public record InboxSourceDto(
        String type,
        boolean configured,
        String appId,
        boolean hasSecret,
        boolean enabled,
        boolean bound,
        String bindCode,
        String status,
        String lastError,
        Instant updatedAt,
        int today,
        Instant lastMessageAt) {
}
