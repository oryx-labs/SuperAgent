package com.superagent.api.chat;

/**
 * An image attached to an outgoing message.
 *
 * @param mediaType image/png, image/jpeg, image/gif or image/webp
 * @param data      the image bytes, base64-encoded (no {@code data:} prefix)
 */
public record ImageUploadDto(String mediaType, String data) {
}
