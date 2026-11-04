package com.superagent.api.inbox;

import java.time.Instant;

/** A received message as listed in the inbox. */
public record InboxEventDto(String id, String source, String chatName, String chatType, String senderName, String text,
                            boolean mentionsMe, String priority, String triageReason, boolean processed,
                            Instant receivedAt) {
}
