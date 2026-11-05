package com.superagent.runtime.attachment;

import com.superagent.core.error.NotFoundException;

/** Thrown when a requested message image does not exist; mapped to HTTP 404. */
public class AttachmentNotFoundException extends NotFoundException {

    public AttachmentNotFoundException(String messageId, int index) {
        super("No image " + index + " on message " + messageId);
    }
}
