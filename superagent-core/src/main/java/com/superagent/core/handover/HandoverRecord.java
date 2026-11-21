package com.superagent.core.handover;

import java.time.Instant;

/**
 * Audit row for one employee handover: who left, who took over, who ran it, and a JSON summary of what moved.
 * Ownership fields elsewhere use user ids; usernames here are for humans reading the audit.
 */
public record HandoverRecord(
        String id,
        String fromUserId,
        String fromUsername,
        String toUserId,
        String toUsername,
        String byUserId,
        String status,
        String summaryJson,
        Instant createdAt,
        Instant finishedAt) {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    public HandoverRecord completed(String summaryJson) {
        return new HandoverRecord(id, fromUserId, fromUsername, toUserId, toUsername, byUserId, COMPLETED,
                summaryJson, createdAt, Instant.now());
    }

    public HandoverRecord failed(String summaryJson) {
        return new HandoverRecord(id, fromUserId, fromUsername, toUserId, toUsername, byUserId, FAILED,
                summaryJson, createdAt, Instant.now());
    }
}
