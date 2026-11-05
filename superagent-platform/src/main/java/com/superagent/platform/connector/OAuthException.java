package com.superagent.platform.connector;

/**
 * A recoverable OAuth-flow failure (not configured, bad state, exchange failed).
 * The code is a stable slug the UI turns into a message; it carries no secrets.
 */
public class OAuthException extends RuntimeException {

    private final String code;

    public OAuthException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
