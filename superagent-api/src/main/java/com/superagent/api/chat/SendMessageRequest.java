package com.superagent.api.chat;

/** One user message to send; the reply streams back as Server-Sent Events. */
public record SendMessageRequest(String content) {
}
