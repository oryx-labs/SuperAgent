package com.superagent.api.chat;

/** Request to edit a message's stored content in place. */
public record EditMessageRequest(String content) {
}
