package com.superagent.core.project;

import java.time.Instant;

/** One person in a project; removing someone keeps the row, inactive. */
public record ProjectMember(String projectId, String username, String addedBy, boolean active, Instant createdAt,
                            Instant updatedAt) {
}
