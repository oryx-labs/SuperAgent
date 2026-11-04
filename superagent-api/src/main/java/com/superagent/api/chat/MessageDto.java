package com.superagent.api.chat;

import java.time.Instant;
import java.util.List;

/** A settled chat message returned to clients. {@code images} are URLs of the images attached to it. */
public record MessageDto(
        String id,
        String role,
        String content,
        Instant createdAt,
        List<String> images) {

    public MessageDto {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public MessageDto(String id, String role, String content, Instant createdAt) {
        this(id, role, content, createdAt, List.of());
    }
}
