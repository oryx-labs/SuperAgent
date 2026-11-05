package com.superagent.api.chat;

import java.time.Instant;

/**
 * Whether the agent is answering right now, who asked, and its text so far — polled by members of a
 * shared agent who didn't start the turn. {@code sessionId} is the agent's current session, so a viewer
 * notices when the conversation moved on to a new one.
 */
public record LiveTurnDto(boolean running, String by, String byName, Instant startedAt, String text, String activity,
                          String sessionId) {
}
