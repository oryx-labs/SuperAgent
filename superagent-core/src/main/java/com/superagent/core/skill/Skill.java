package com.superagent.core.skill;

import java.time.Instant;

/**
 * A reusable working standard (writing style guide, diagram conventions, ...) that agents follow.
 * {@code description} says when it applies — it is all an agent sees up front; {@code content}
 * (Markdown, may reference the skill's images as {@code images/<name>}) is read only when relevant.
 * Skills are shared: anyone can create or edit one; {@code deleted} is a soft delete.
 */
public record Skill(
        String id,
        String name,
        String description,
        String category,
        String content,
        boolean enabled,
        boolean deleted,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public Skill withDeleted(String by) {
        return new Skill(id, name, description, category, content, enabled, true, createdBy, by, createdAt, Instant.now());
    }
}
