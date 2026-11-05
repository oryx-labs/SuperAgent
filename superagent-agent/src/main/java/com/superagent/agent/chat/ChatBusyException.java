package com.superagent.agent.chat;

import com.superagent.core.error.BusyException;

/** A turn is already running for this agent (possibly started by another member); mapped to HTTP 409. */
public class ChatBusyException extends BusyException {

    public ChatBusyException(String message) {
        super(message);
    }
}
