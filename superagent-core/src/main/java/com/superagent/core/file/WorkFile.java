package com.superagent.core.file;

import java.time.Instant;
import java.util.List;

/**
 * A work result an agent produced: a document, an image, a video — "everything is a file". Only the latest
 * content is kept (an agent revising a file overwrites it). {@code storage} says where the content lives
 * ({@link #INTERNAL} = on this server's disk; another {@link FileStoreProvider#type()} = that store, e.g. "s3";
 * {@link #LINK} = a web address, {@code location} is the URL). For stored content {@code location} is the id the
 * store knows it by.
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
        Instant updatedAt,
        /** Topic tags for finding files again; who set them is {@code tagState}. */
        List<String> tags,
        /** "" = not tagged yet (the tagger picks it up), agent, auto (the tagger), user (a person's tags win). */
        String tagState) {

    public WorkFile {
        tags = tags == null ? List.of() : List.copyOf(tags);
        tagState = tagState == null ? TAGS_PENDING : tagState;
    }

    public static final String TAGS_PENDING = "";
    public static final String TAGS_AGENT = "agent";
    public static final String TAGS_AUTO = "auto";
    public static final String TAGS_USER = "user";
    public static final int MAX_TAGS = 5;
    public static final int MAX_TAG_LENGTH = 20;

    /** Tags as entered by anyone: trimmed, "#" dropped, deduplicated case-insensitively, capped in count and length. */
    public static List<String> cleanTags(List<String> raw) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (String t : raw == null ? List.<String>of() : raw) {
            String c = t == null ? "" : t.replace("#", "").replaceAll("[\\s,，;；]+", " ").strip();
            if (c.length() > MAX_TAG_LENGTH) {
                c = c.substring(0, MAX_TAG_LENGTH).strip();
            }
            if (!c.isEmpty()) {
                out.putIfAbsent(c.toLowerCase(java.util.Locale.ROOT), c);
            }
            if (out.size() == MAX_TAGS) {
                break;
            }
        }
        return List.copyOf(out.values());
    }

    public WorkFile withTags(List<String> newTags, String newState) {
        return new WorkFile(id, name, kind, mimeType, size, storage, location, owner, agentId, taskId, origin,
                deleted, createdAt, updatedAt, cleanTags(newTags), newState);
    }

    public static final String INTERNAL = "internal";
    /** A web address someone shared: {@code location} is the URL; there's no content on this server. */
    public static final String LINK = "link";
    public static final String ORIGIN_CHAT = "chat";
    public static final String ORIGIN_TASK = "task";
    /** A task's final reply, saved as a file by older versions; a task's reply is its output now, not a file. */
    public static final String ORIGIN_DELIVERY = "delivery";
    /** Attached by a person to a message (not made by an agent). */
    public static final String ORIGIN_UPLOAD = "upload";

    public WorkFile withContent(String newName, WorkFileKind newKind, String newMime, long newSize) {
        return new WorkFile(id, newName, newKind, newMime, newSize, storage, location, owner, agentId, taskId, origin,
                deleted, createdAt, Instant.now(), tags, tagState);
    }

    /** The same file, its content now kept in another store. */
    public WorkFile withStorage(String newStorage) {
        return new WorkFile(id, name, kind, mimeType, size, newStorage, location, owner, agentId, taskId, origin,
                deleted, createdAt, Instant.now(), tags, tagState);
    }

    public WorkFile markDeleted() {
        return new WorkFile(id, name, kind, mimeType, size, storage, location, owner, agentId, taskId, origin,
                true, createdAt, Instant.now(), tags, tagState);
    }
}
