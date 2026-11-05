package com.superagent.core.notice;

/**
 * Domain events the to-do page is built from. Modules publish them (Spring application events) when
 * something happens; the notice service listens, so no module needs to know about the to-do page.
 */
public final class NoticeEvents {

    private NoticeEvents() {
    }

    /**
     * A task changed status. {@code agentId} is set when an agent created it (only at creation);
     * {@code status} is the lower-case task status; {@code result} is the agent's final reply when it finished.
     */
    public record TaskStatusChanged(String taskId, String owner, String title, String source, String status,
                                    String error, String agentId, String result) {

        public TaskStatusChanged(String taskId, String owner, String title, String source, String status,
                                 String error, String agentId) {
            this(taskId, owner, title, source, status, error, agentId, null);
        }
    }

    /** The user's SuperAgent proposed a plan ({@code open}) or the plan was approved / replaced. */
    public record PlanChanged(String owner, String planId, String summary, boolean open) {
    }

    /** A to-do extracted from received messages was created or changed; status is open | tasked | done | dismissed. */
    public record InboxTodoChanged(String todoId, String owner, String title, String detail, String requester,
                                   String due, String source, String status, String taskId) {
    }

    /** {@code username} was invited to collaborate on someone else's agent. */
    public record AgentShared(String agentId, String agentName, String username, String byName) {
    }

    /** The user ignored an item; the module that owns what it is about may react (e.g. dismiss the to-do). */
    public record NoticeIgnored(String owner, String linkType, String linkId) {
    }
}
