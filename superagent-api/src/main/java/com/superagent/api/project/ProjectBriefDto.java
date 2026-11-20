package com.superagent.api.project;

import java.time.Instant;

/**
 * A project's brief: goal, background, norms and glossary. {@code agentId}/{@code agentName} are set when an agent
 * made the last change on {@code updatedBy}'s behalf.
 */
public record ProjectBriefDto(String goal, String background, String norms, String glossary, String updatedBy,
                              String updatedByName, Instant updatedAt, String agentId, String agentName) {
}
