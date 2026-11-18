package com.superagent.api.project;

import java.time.Instant;

/** One project memory entry and where it came from (the agent, its conversation or task, who asked for it). */
public record ProjectMemoryDto(String id, String title, String content, String agentId, String agentName, String sessionId,
                               String taskId, String createdBy, String createdByName, String updatedBy, Instant createdAt,
                               Instant updatedAt) {
}
