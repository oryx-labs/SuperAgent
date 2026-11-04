package com.superagent.api.inbox;

import java.time.Instant;

/**
 * A user's Feishu bot settings for clients. The app secret is never returned — only whether one is set.
 * {@code bound} says the owner sent the bot the bind code; {@code bindCode} is shown until then.
 */
public record InboxSourceDto(
        String type,
        boolean configured,
        String appId,
        boolean hasSecret,
        boolean enabled,
        boolean bound,
        String bindCode,
        String vipNames,
        String urgentKeywords,
        String digestTime,
        String timezone,
        String secretaryAgentId,
        String status,
        String lastError,
        Instant updatedAt) {
}
