package com.superagent.api.project;

import java.time.Instant;
import java.util.List;

/** A project as its members see it: who is in it, which agents it holds, and how much it has produced. */
public record ProjectDto(
        String id,
        String name,
        String description,
        String owner,
        String ownerName,
        String status,
        boolean mine,
        List<Member> members,
        List<String> agentIds,
        int taskCount,
        int fileCount,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public record Member(String username, String displayName, boolean owner) {
    }
}
