package com.superagent.api.task;

import java.time.Instant;

/**
 * One event in a task's execution stream. Like the chat stream DTO but carries
 * {@code createdAt}, so the detail view can interleave agent events with the
 * user's steering messages in true chronological order.
 */
public record TaskEventDto(
        String type,
        long seq,
        String payload,
        Instant createdAt) {
}
