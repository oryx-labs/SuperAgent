package com.superagent.api.skill;

/** An image to add to a skill: base64 bytes (no {@code data:} prefix); the format is sniffed, not trusted. */
public record SkillImageUpload(String name, String data) {
}
