package com.superagent.api.file;

import java.time.Instant;
import java.util.List;

/**
 * A work file as the UI shows it. {@code kind} is document | image | video | link | other; {@code origin} chat |
 * task | delivery | upload (a person sent it). {@code url} is set for a link. Where it came from is resolved to names ({@code agentName}, {@code taskTitle}, {@code ownerName}).
 * {@code messageIds} are the chat replies that produced it, for the file cards in a conversation;
 * {@code canManage} whether the caller may rename or delete it.
 */
public record WorkFileDto(
        String id,
        String name,
        String kind,
        String mimeType,
        long size,
        String storage,
        String origin,
        String owner,
        String ownerName,
        String agentId,
        String agentName,
        String agentAvatar,
        String taskId,
        String taskTitle,
        List<String> messageIds,
        boolean canManage,
        Instant createdAt,
        Instant updatedAt,
        List<String> tags,
        /** "" (not tagged yet), agent, auto or user. */
        String tagState,
        String url,
        /** Where the caller keeps it (folders are per person), or null when unfiled. */
        String folderId) {
}
