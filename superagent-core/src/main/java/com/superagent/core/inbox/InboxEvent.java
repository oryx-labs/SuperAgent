package com.superagent.core.inbox;

import java.time.Instant;

/**
 * One message SuperAgent received on its owner's behalf, normalized across sources. The original
 * payload is kept in {@code raw} so a better triage or digest can be re-run later. {@code source} +
 * {@code externalId} identify it at the source (sources redeliver, so ingest dedupes on that pair).
 *
 * <p>Message text is untrusted third-party input: it is only ever handed to a model as data.
 */
public record InboxEvent(
        String id,
        String owner,
        String source,
        String externalId,
        String chatId,
        String chatName,
        /** "p2p" (direct message) or "group". */
        String chatType,
        String threadId,
        String senderId,
        String senderName,
        String text,
        /** A direct message, or a mention of the owner or their bot. */
        boolean mentionsMe,
        String raw,
        EventPriority priority,
        String triageReason,
        boolean processed,
        Instant occurredAt,
        Instant receivedAt,
        Instant updatedAt) {

    public InboxEvent processedNow() {
        return new InboxEvent(id, owner, source, externalId, chatId, chatName, chatType, threadId, senderId, senderName,
                text, mentionsMe, raw, priority, triageReason, true, occurredAt, receivedAt, Instant.now());
    }
}
