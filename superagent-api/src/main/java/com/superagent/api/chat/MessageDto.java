package com.superagent.api.chat;

import java.time.Instant;
import java.util.List;

/** A settled chat message returned to clients. {@code images} are URLs of the images attached to it. */
public record MessageDto(
        String id,
        String role,
        String content,
        Instant createdAt,
        List<String> images,
        /** Username of who wrote a user message (shared agents show it); null for replies. */
        String author,
        /** The author's display name, for showing. */
        String authorName) {

    public MessageDto {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public MessageDto(String id, String role, String content, Instant createdAt, List<String> images) {
        this(id, role, content, createdAt, images, null, null);
    }

    public MessageDto(String id, String role, String content, Instant createdAt) {
        this(id, role, content, createdAt, List.of());
    }
}
