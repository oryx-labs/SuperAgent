package com.superagent.api.inbox;

import java.time.Instant;

/**
 * A received message as listed in the message stream. {@code handling}: what SuperAgent did with it —
 * delivered, notified, todo, task, read or ignored; null while it waits for its batch.
 */
public record InboxEventDto(String id, String source, String chatName, String chatType, String senderName, String text,
                            boolean mentionsMe, String priority, String triageReason, boolean processed,
                            String handling, Instant receivedAt) {
}
