package com.superagent.web.chat;

/** Thrown when a chat session id does not resolve; mapped to HTTP 404. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("No such session: " + sessionId);
    }
}
