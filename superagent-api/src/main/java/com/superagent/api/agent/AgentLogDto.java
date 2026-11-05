package com.superagent.api.agent;

import java.time.Instant;

/**
 * One line of an agent's run log. {@code level}: info | warn | error; {@code category}: session | turn |
 * memory | superagent. {@code detail} is extra text shown when the line is expanded, or null.
 */
public record AgentLogDto(String id, Instant at, String level, String category, String summary, String detail) {
}
