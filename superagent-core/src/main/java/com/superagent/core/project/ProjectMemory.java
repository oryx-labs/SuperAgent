package com.superagent.core.project;

import java.time.Instant;

/**
 * One thing a project remembers: a decision, a convention, a pitfall. Provenance says where it came from: the agent
 * that wrote it (null when a person did), the conversation or task it was written in, and who asked for it.
 */
public record ProjectMemory(
        String id,
        String projectId,
        String title,
        String content,
        String agentId,
        String sessionId,
        String taskId,
        String createdBy,
        String updatedBy,
        boolean deleted,
        Instant createdAt,
        Instant updatedAt) {

    public ProjectMemory edited(String title, String content, String by) {
        return new ProjectMemory(id, projectId, title, content, agentId, sessionId, taskId, createdBy, by, false, createdAt, Instant.now());
    }

    public ProjectMemory asDeleted(String by) {
        return new ProjectMemory(id, projectId, title, content, agentId, sessionId, taskId, createdBy, by, true, createdAt, Instant.now());
    }
}
