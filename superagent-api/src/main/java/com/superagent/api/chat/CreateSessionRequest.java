package com.superagent.api.chat;

/** Request to open a new chat session; all fields optional (agentType defaults to claude). */
public record CreateSessionRequest(String agentType, String title, String model) {
}
