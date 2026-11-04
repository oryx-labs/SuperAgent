package com.superagent.api.skill;

/** Create or update a skill; on update, a null field keeps its current value. */
public record SkillRequest(String name, String description, String category, String content, Boolean enabled) {
}
