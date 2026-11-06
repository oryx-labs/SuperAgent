package com.superagent.api.skill;

import java.time.Instant;
import java.util.List;

/**
 * A skill as the caller sees it. {@code images} are file names usable in {@code content} as {@code images/<name>};
 * {@code space} is {@code personal} or {@code company}, and {@code canWrite} whether the caller
 * may change it.
 */
public record SkillDto(
        String id,
        String name,
        String description,
        String category,
        String content,
        boolean enabled,
        String space,
        boolean canWrite,
        List<String> images,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
