package com.superagent.core.chat;

/**
 * An image the user attached to a message, already validated and stored on local disk.
 * Runners hand it to their CLI in whatever form it takes (inline base64 for Claude,
 * a file path for Codex), so the file must outlive the turn.
 *
 * @param path      absolute path of the stored image
 * @param mediaType image/png, image/jpeg, image/gif or image/webp
 */
public record ImageAttachment(String path, String mediaType) {
}
