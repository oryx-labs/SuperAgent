package com.superagent.core.inbox;

import java.util.List;

/**
 * Turns a batch of triaged messages into something useful for the owner: a summary posted to their
 * secretary chat and extracted to-dos. Implemented by the web layer (it runs a model); the event
 * module only decides <em>when</em> to call it.
 */
public interface InboxProcessor {

    /** The outcome; {@code notifyText} is a short line to push to the owner at the source, or null. */
    record Result(String notifyText) {
    }

    /** {@code window}: P0 = one urgent message now, P1 = the hourly batch, P2 = the daily digest. */
    Result process(EventSource source, EventPriority window, List<InboxEvent> events);
}
