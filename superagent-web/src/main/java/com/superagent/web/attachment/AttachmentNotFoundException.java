package com.superagent.web.attachment;

/** Thrown when a requested message image does not exist; mapped to HTTP 404. */
public class AttachmentNotFoundException extends RuntimeException {

    public AttachmentNotFoundException(String messageId, int index) {
        super("No image " + index + " on message " + messageId);
    }
}
