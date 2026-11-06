package com.superagent.api.skill;

/**
 * Create or update a skill; on update, a null field keeps its current value. {@code space} is where it lives:
 * {@code personal} (the default on create) or {@code company}.
 */
public record SkillRequest(String name, String description, String category, String content, Boolean enabled,
                           String space) {
}
