package com.superagent.core.file;

import java.time.Instant;

/**
 * A folder in one person's Files, so a person's tree is theirs alone and its names are seen by nobody else.
 * {@code parentId} null is the top level. Only a label: a file's content doesn't move with it.
 */
public record FileFolder(String id, String owner, String name, String parentId, boolean deleted,
                         Instant createdAt, Instant updatedAt) {

    public FileFolder withName(String newName) {
        return new FileFolder(id, owner, newName, parentId, deleted, createdAt, Instant.now());
    }

    public FileFolder withParent(String newParentId) {
        return new FileFolder(id, owner, name, newParentId, deleted, createdAt, Instant.now());
    }

    public FileFolder asDeleted() {
        return new FileFolder(id, owner, name, parentId, true, createdAt, Instant.now());
    }
}
