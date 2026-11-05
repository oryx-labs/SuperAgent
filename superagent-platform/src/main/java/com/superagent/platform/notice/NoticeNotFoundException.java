package com.superagent.platform.notice;

import com.superagent.core.error.NotFoundException;

/** A to-do item that doesn't exist or isn't the caller's; mapped to HTTP 404. */
public class NoticeNotFoundException extends NotFoundException {

    public NoticeNotFoundException(String id) {
        super("No such item: " + id);
    }
}
