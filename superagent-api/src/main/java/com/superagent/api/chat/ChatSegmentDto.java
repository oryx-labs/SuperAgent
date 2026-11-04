package com.superagent.api.chat;

import java.time.Instant;
import java.util.List;

/**
 * One session of an agent's conversation. An agent has many sessions over time (a model change starts
 * a new one); clients show them in order, marking where a new session began.
 */
public record ChatSegmentDto(String sessionId, Instant startedAt, List<MessageDto> messages) {
}
