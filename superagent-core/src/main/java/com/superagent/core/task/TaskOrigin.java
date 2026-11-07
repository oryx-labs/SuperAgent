package com.superagent.core.task;

/**
 * Where a task was set off: the agent whose chat created it, and the conversation and reply it came from
 * ({@code sessionId}/{@code messageId} are null when it didn't come from a chat turn).
 */
public record TaskOrigin(String taskId, String agentId, String sessionId, String messageId) {
}
