package com.superagent.core.agent;

import java.time.Instant;

/**
 * Something that happened to a {@link SuperAgentWork}, append-only. {@code kind}: assigned |
 * needs_confirmation | replied (the SuperAgent answered the task) | done | error | stopped | deleted.
 */
public record SuperAgentWorkEvent(
        String id,
        String taskId,
        String owner,
        String kind,
        String detail,
        Instant at) {
}
