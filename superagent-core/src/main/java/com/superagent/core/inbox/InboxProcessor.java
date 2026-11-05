package com.superagent.core.inbox;

import java.util.List;

/**
 * Takes over a batch of triaged messages for their owner — SuperAgent does (it lives above the inbox, hence
 * the interface down here). The seam also lets the inbox's scheduling be tested without a model.
 */
public interface InboxProcessor {

    /** The outcome; {@code notifyText} is a short line to push to the owner at the source right away, or null. */
    record Result(String notifyText) {
    }

    /** {@code window}: P0 = urgent messages now, P1 = the hourly batch, P2 = the daily digest. */
    Result process(EventSource source, EventPriority window, List<InboxEvent> events);
}
