package com.superagent.core.task;

import java.time.Instant;

/**
 * How a task stands to others, fixed when it is made: {@code parentId} is the complex task it is a subtask of, and
 * {@code continuesFrom} the task whose work it carries on. Either may be null.
 */
public record TaskLink(String taskId, String parentId, String continuesFrom, Instant createdAt) {
}
