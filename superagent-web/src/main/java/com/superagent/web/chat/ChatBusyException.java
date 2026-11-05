package com.superagent.web.chat;

/** A turn is already running for this agent (possibly started by another member); mapped to HTTP 409. */
public class ChatBusyException extends RuntimeException {

    public ChatBusyException(String message) {
        super(message);
    }
}
