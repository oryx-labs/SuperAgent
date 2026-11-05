package com.superagent.api.notice;

import java.time.Instant;

/**
 * A to-do page item. kind: confirm | action | done | notice; state: open | done | ignored.
 * linkType/linkId say what it opens (task, agent, superagent, todo).
 */
public record NoticeDto(String id, String kind, String state, String title, String body, String actor,
                        String linkType, String linkId, Instant createdAt, Instant updatedAt) {
}
