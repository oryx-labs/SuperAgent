package com.superagent.api.chat;

import java.util.List;

/**
 * One user message to send; the reply streams back as Server-Sent Events. {@code images}
 * are optional; with images, {@code content} may be blank.
 */
public record SendMessageRequest(String content, List<ImageUploadDto> images) {

    public SendMessageRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public SendMessageRequest(String content) {
        this(content, List.of());
    }
}
