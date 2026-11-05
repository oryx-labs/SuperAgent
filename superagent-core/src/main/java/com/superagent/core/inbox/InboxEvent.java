package com.superagent.core.inbox;

import java.time.Instant;
import java.util.List;

/**
 * One message SuperAgent received on its owner's behalf, normalized across sources. The original
 * payload is kept in {@code raw} so a better triage or digest can be re-run later. {@code source} +
 * {@code externalId} identify it at the source (sources redeliver, so ingest dedupes on that pair).
 *
 * <p>Message text is untrusted third-party input: it is only ever handed to a model as data.
 * {@code handling} is what SuperAgent did with it (see {@link #HANDLINGS}); null until it's handed over.
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
        String handling,
        Instant occurredAt,
        Instant receivedAt,
        Instant updatedAt) {

    /** delivered: handed to SuperAgent; then notified, todo, task (a plan proposed for it), read or ignored. */
    public static final List<String> HANDLINGS =
            List.of("delivered", "notified", "todo", "task", "read", "ignored");

    /** Handed to SuperAgent; what it then does is recorded with {@link #handledAs}. */
    public InboxEvent processedNow() {
        return new InboxEvent(id, owner, source, externalId, chatId, chatName, chatType, threadId, senderId, senderName,
                text, mentionsMe, raw, priority, triageReason, true, "delivered", occurredAt, receivedAt, Instant.now());
    }

    /** The short id SuperAgent sees and refers to the message by. */
    public String ref() {
        return id.substring(0, Math.min(8, id.length()));
    }

    public InboxEvent handledAs(String newHandling) {
        return new InboxEvent(id, owner, source, externalId, chatId, chatName, chatType, threadId, senderId, senderName,
                text, mentionsMe, raw, priority, triageReason, processed, newHandling, occurredAt, receivedAt, Instant.now());
    }
}
