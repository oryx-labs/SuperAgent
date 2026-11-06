package com.superagent.api.inbox;

/** The SuperAgent panel's line about outside messages: whether the user is linked to Feishu, and today's messages. */
public record InboxSummaryDto(boolean linked, int today, int handled) {
}
