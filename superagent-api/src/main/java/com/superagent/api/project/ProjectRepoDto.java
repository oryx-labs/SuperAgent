package com.superagent.api.project;

import java.time.Instant;

/**
 * A code repository a project works on; {@code delivery} is merge or pr. The last change: who, when, and through
 * which agent if one made it.
 */
public record ProjectRepoDto(String id, String name, String location, String defaultBranch, String delivery, String purpose,
                             String updatedBy, String updatedByName, Instant updatedAt, String agentId, String agentName) {
}
