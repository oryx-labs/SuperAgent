package com.superagent.api.inbox;

import java.time.Instant;

/** A to-do extracted from received messages; status is open | tasked | done | dismissed. */
public record InboxTodoDto(String id, String title, String detail, String requester, String due, String source,
                           String status, String taskId, Instant createdAt) {
}
