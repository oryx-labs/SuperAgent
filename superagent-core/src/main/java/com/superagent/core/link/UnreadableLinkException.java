package com.superagent.core.link;

/** A link that could not be read: why, and what the user can do about it (authorize, make it public, send the file). */
public class UnreadableLinkException extends RuntimeException {

    private final String url;
    private final String hint;

    public UnreadableLinkException(String url, String reason, String hint) {
        super(reason);
        this.url = url;
        this.hint = hint;
    }

    public String url() {
        return url;
    }

    public String hint() {
        return hint;
    }
}
