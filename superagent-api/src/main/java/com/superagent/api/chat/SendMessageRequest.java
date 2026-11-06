package com.superagent.api.chat;

import java.util.List;

/**
 * One user message to send; the reply streams back as Server-Sent Events. {@code images} (inline) and
 * {@code fileIds} (uploaded first, see the files API) are optional; with either, {@code content} may be blank.
 */
public record SendMessageRequest(String content, List<ImageUploadDto> images, List<String> fileIds) {

    public SendMessageRequest {
        images = images == null ? List.of() : List.copyOf(images);
        fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
    }

    public SendMessageRequest(String content) {
        this(content, List.of(), List.of());
    }
}
