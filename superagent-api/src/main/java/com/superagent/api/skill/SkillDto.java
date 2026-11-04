package com.superagent.api.skill;

import java.time.Instant;
import java.util.List;

/** A shared skill. {@code images} are file names usable in {@code content} as {@code images/<name>}. */
public record SkillDto(
        String id,
        String name,
        String description,
        String category,
        String content,
        boolean enabled,
        List<String> images,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
