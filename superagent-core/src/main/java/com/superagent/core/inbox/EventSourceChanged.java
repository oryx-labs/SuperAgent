package com.superagent.core.inbox;

/** Published when a user saves their source settings, so the connector (re)connects with them. */
public record EventSourceChanged(String owner, String type) {
}
