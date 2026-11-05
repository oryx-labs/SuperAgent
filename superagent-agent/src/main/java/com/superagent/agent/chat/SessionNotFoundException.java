package com.superagent.agent.chat;

import com.superagent.core.error.NotFoundException;

/** Thrown when a chat session id does not resolve; mapped to HTTP 404. */
public class SessionNotFoundException extends NotFoundException {

    public SessionNotFoundException(String sessionId) {
        super("No such session: " + sessionId);
    }
}
