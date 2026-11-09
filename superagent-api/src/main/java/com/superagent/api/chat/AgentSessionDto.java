package com.superagent.api.chat;

import java.time.Instant;

/**
 * One of an agent's sessions, for its owner's run log: which model it ran on, why it was opened
 * ({@code first} / {@code model} / {@code model_down} / {@code failures}; null for sessions from before this was kept),
 * how many replies it gave, and how often the CLI couldn't resume and started over inside it.
 */
public record AgentSessionDto(String id, int number, boolean current, String model, String reason, int cliRestarts,
                              int turns, Instant startedAt, Instant lastAt) {
}
