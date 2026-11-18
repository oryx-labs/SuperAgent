package com.superagent.core.file;

import java.time.Instant;

/** A folder in a project's shared tree; {@code parentId} null = top level. Deleting keeps the row, flagged. */
public record ProjectFolder(String id, String projectId, String name, String parentId, boolean deleted, Instant createdAt,
                            Instant updatedAt) {

    public ProjectFolder withName(String next) {
        return new ProjectFolder(id, projectId, next, parentId, deleted, createdAt, Instant.now());
    }

    public ProjectFolder withParent(String next) {
        return new ProjectFolder(id, projectId, name, next, deleted, createdAt, Instant.now());
    }

    public ProjectFolder asDeleted() {
        return new ProjectFolder(id, projectId, name, parentId, true, createdAt, Instant.now());
    }
}
