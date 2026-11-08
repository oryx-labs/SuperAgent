package com.superagent.core.project;

import java.time.Instant;

/**
 * A container people work in together: a set of agents, the people who use them, and the tasks and files those agents
 * produce. The owner manages it; members can use its agents and see everything in it.
 */
public record Project(
        String id,
        String name,
        String description,
        String owner,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public boolean archived() {
        return status == ProjectStatus.ARCHIVED;
    }

    public Project withDetails(String name, String description) {
        return new Project(id, name, description, owner, status, createdAt, Instant.now(), archivedAt);
    }

    public Project withStatus(ProjectStatus next) {
        Instant now = Instant.now();
        return new Project(id, name, description, owner, next, createdAt, now, next == ProjectStatus.ARCHIVED ? now : null);
    }
}
