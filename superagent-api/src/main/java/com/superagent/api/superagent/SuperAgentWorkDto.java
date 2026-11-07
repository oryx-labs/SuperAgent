package com.superagent.api.superagent;

import java.time.Instant;

/**
 * A task the SuperAgent assigned: who did it ({@code memberAvatar} null if that member is gone), how it
 * stands ({@code status}: running | needs_confirmation | done | error | stopped | deleted) and a short
 * {@code summary} of the outcome; {@code taskId} opens the task itself. {@code planId} is null for
 * back-filled work. {@code messageId} is the SuperAgent chat reply that handed it out (null when the background or
 * an older version did); {@code byMember} is false for an independent task, which has no member to open.
 */
public record SuperAgentWorkDto(
        String taskId,
        String planId,
        String title,
        String memberId,
        String memberName,
        String memberAvatar,
        String status,
        String summary,
        Instant assignedAt,
        Instant finishedAt,
        String messageId,
        boolean byMember) {
}
