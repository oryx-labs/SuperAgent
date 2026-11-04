package com.superagent.web.superagent;

import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.task.TaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** The SuperAgent's timing and follow-up rules, kept free of I/O so they can be checked on their own. */
final class SuperAgentInbox {

    /** What to do with an assigned task the SuperAgent is watching. */
    enum Watch {
        /** Still working: nothing to say yet. */
        KEEP,
        /** Needs a decision: tell the user, but keep watching (it can still finish). */
        REPORT_AND_KEEP,
        /** Finished, failed, stopped or gone: tell the user and stop watching. */
        REPORT_AND_DROP,
        /** Already told and settled: stop watching quietly. */
        DROP
    }

    /** User messages and system notes after the cursor, oldest first (replies are the agent's own). */
    static List<ChatMessage> pending(List<ChatMessage> all, Instant after) {
        return all.stream()
                .filter(m -> m.role() != MessageRole.ASSISTANT)
                .filter(m -> after == null || m.createdAt().isAfter(after))
                .toList();
    }

    /**
     * A round starts once input has gone quiet for {@code quiet}, or the oldest waiting message has waited
     * {@code maxWait} (so someone who keeps typing still gets an answer), or right away when asked to.
     */
    static boolean due(List<ChatMessage> pending, Instant now, Duration quiet, Duration maxWait, boolean immediate) {
        if (pending.isEmpty()) {
            return false;
        }
        Instant first = pending.get(0).createdAt();
        Instant last = pending.get(pending.size() - 1).createdAt();
        return immediate || !last.plus(quiet).isAfter(now) || !first.plus(maxWait).isAfter(now);
    }

    /** {@code reported} is the status last reported for this task (null = nothing reported yet). */
    static Watch watch(TaskStatus status, String reported) {
        boolean settled = status == TaskStatus.DONE || status == TaskStatus.ERROR
                || status == TaskStatus.STOPPED || status == TaskStatus.DELETED;
        boolean news = !status.name().equals(reported);
        if (settled) {
            return news ? Watch.REPORT_AND_DROP : Watch.DROP;
        }
        if (status == TaskStatus.NEEDS_CONFIRMATION && news) {
            return Watch.REPORT_AND_KEEP;
        }
        return Watch.KEEP;
    }

    private SuperAgentInbox() {
    }
}
