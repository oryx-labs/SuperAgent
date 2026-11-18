package com.superagent.api.project;

import java.time.Instant;

/** A project's brief: goal, background, norms and glossary. */
public record ProjectBriefDto(String goal, String background, String norms, String glossary, String updatedBy,
                              String updatedByName, Instant updatedAt) {
}
