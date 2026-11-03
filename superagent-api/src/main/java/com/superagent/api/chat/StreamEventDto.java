package com.superagent.api.chat;

/**
 * One SSE event sent while an agent produces a reply.
 * {@code type} is one of: system, thinking, text, tool_use, result, stopped, error, done.
 */
public record StreamEventDto(String type, long seq, String payload) {
}
