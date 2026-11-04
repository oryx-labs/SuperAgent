package com.superagent.core.inbox;

import java.time.Instant;
import java.util.List;

public interface InboxEventStore {

    /** Stores the event unless one with the same source + externalId exists; true when it was new. */
    boolean insertIfAbsent(InboxEvent event);

    void update(InboxEvent event);

    /** The owner's unprocessed events of this priority, oldest first. */
    List<InboxEvent> pending(String owner, EventPriority priority, int limit);

    /** The owner's most recent events, newest first. */
    List<InboxEvent> recent(String owner, int limit);

    /** Drops events received before {@code cutoff}; returns how many. */
    int deleteBefore(Instant cutoff);
}
