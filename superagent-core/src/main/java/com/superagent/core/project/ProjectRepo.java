package com.superagent.core.project;

import java.time.Instant;

/**
 * A code repository a project works on. {@code location} is a local path or a git address; {@code delivery} is how a
 * coding task hands its work back: {@link #MERGE} into the local branch, or {@link #PR}. The last change was made by
 * {@code updatedBy}, through {@code agentId} (in that conversation or task) when an agent made it.
 */
public record ProjectRepo(
        String id,
        String projectId,
        String name,
        String location,
        String defaultBranch,
        String delivery,
        String purpose,
        int position,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy,
        String agentId,
        String sessionId,
        String taskId) {

    public static final String MERGE = "merge";
    public static final String PR = "pr";

    public ProjectRepo(String id, String projectId, String name, String location, String defaultBranch, String delivery,
                       String purpose, int position, Instant createdAt, Instant updatedAt) {
        this(id, projectId, name, location, defaultBranch, delivery, purpose, position, createdAt, updatedAt, null, null, null, null);
    }
}
