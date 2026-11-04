package com.superagent.core.inbox;

import java.time.Instant;

/**
 * A to-do extracted from received messages. {@code eventIds} (comma-separated) point back at the
 * messages it came from, so a wrong extraction can be checked against the source.
 */
public record InboxTodo(
        String id,
        String owner,
        String title,
        String detail,
        String requester,
        String due,
        String eventIds,
        String source,
        TodoStatus status,
        String taskId,
        Instant createdAt,
        Instant updatedAt) {

    public enum TodoStatus { OPEN, TASKED, DONE, DISMISSED }

    public InboxTodo withStatus(TodoStatus newStatus, String newTaskId) {
        return new InboxTodo(id, owner, title, detail, requester, due, eventIds, source, newStatus,
                newTaskId != null ? newTaskId : taskId, createdAt, Instant.now());
    }
}
