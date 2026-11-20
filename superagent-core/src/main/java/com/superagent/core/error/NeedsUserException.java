package com.superagent.core.error;

/**
 * A problem no retry or stronger model can fix until someone acts (a file too big without ffmpeg, a format it can't
 * read, no key): a task stops and asks instead of failing. The message says what is wrong and what to do. An
 * {@link IllegalArgumentException}, so callers that already treat those as "the user can fix it" keep working.
 */
public class NeedsUserException extends IllegalArgumentException {

    public NeedsUserException(String message) {
        super(message);
    }
}
