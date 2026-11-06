package com.superagent.core.file;

import java.time.Instant;

/**
 * A work result an agent produced: a document, an image, a video — "everything is a file". Only the latest
 * content is kept (an agent revising a file overwrites it). {@code storage} says where the content lives
 * ({@link #INTERNAL} = on this server, at {@code location}); external stores (Feishu / DingTalk docs, image
 * hosts) come later as further storage kinds whose {@code location} is a link.
 *
 * <p>Provenance: {@code owner} is who gave the instruction (the file is theirs), {@code agentId} the agent
 * that made it (its members can see it), {@code taskId} the task, if it came from one. {@code origin} is
 * chat, task, delivery (a task's final result, captured automatically), or upload (a person attached it to a
 * message: {@code owner} is who sent it, {@code agentId} the agent it was sent to).
 */
public record WorkFile(
        String id,
        String name,
        WorkFileKind kind,
        String mimeType,
        long size,
        String storage,
        String location,
        String owner,
        String agentId,
        String taskId,
        String origin,
        boolean deleted,
        Instant createdAt,
        Instant updatedAt) {

    public static final String INTERNAL = "internal";
    public static final String ORIGIN_CHAT = "chat";
    public static final String ORIGIN_TASK = "task";
    public static final String ORIGIN_DELIVERY = "delivery";
    /** Attached by a person to a message (not made by an agent). */
    public static final String ORIGIN_UPLOAD = "upload";

    public WorkFile withContent(String newName, WorkFileKind newKind, String newMime, long newSize) {
        return new WorkFile(id, newName, newKind, newMime, newSize, storage, location, owner, agentId, taskId, origin,
                deleted, createdAt, Instant.now());
    }

    public WorkFile markDeleted() {
        return new WorkFile(id, name, kind, mimeType, size, storage, location, owner, agentId, taskId, origin,
                true, createdAt, Instant.now());
    }
}
