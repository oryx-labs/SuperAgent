package com.superagent.core.task;

import java.util.List;
import java.util.Optional;

/**
 * Which agent spawned which task. A chat agent that creates a task through its MCP tools is recorded here,
 * so the agent's panel can list the work it set off. The link is permanent; the task's own lifecycle
 * (including soft delete) stays in {@link TaskStore}.
 */
public interface TaskAgentStore {

    /** Records that {@code taskId} was created via {@code agentId} (no-op if either is null). */
    default void link(String taskId, String agentId) {
        link(taskId, agentId, null, null);
    }

    /** As {@link #link(String, String)}, also remembering the chat reply that created it. */
    void link(String taskId, String agentId, String sessionId, String messageId);

    /** Task ids this agent spawned, newest first. */
    List<String> tasksForAgent(String agentId);

    /** The agent that spawned this task, if one did. */
    Optional<String> agentOf(String taskId);

    /** Where this task was set off, if an agent did. */
    Optional<TaskOrigin> origin(String taskId);

    /** Where each task this agent spawned was set off, newest first. */
    List<TaskOrigin> originsForAgent(String agentId);
}
