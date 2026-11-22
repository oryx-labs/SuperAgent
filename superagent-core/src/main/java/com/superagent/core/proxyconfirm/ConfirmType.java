package com.superagent.core.proxyconfirm;

/** What SuperAgent may confirm on a user's behalf, by tier (1 = default on, 2 = opt-in with a trace, 3 = never). */
public enum ConfirmType {
    TASK_PARAMS(1, false),
    CODING_SPEC(1, false),
    WRITING_OUTLINE(1, false),
    MERGE_LOCAL(2, false),
    CONTINUATION(2, true),
    SUBTASK_SPLIT(2, true),
    OPEN_PR(3, false),
    COLLABORATOR(3, false),
    DELETE(3, false),
    OVER_BUDGET(3, false),
    CREDENTIALS(3, false);

    private final int tier;
    private final boolean revocable;

    ConfirmType(int tier, boolean revocable) {
        this.tier = tier;
        this.revocable = revocable;
    }

    public int tier() {
        return tier;
    }

    /** Whether a confirmation of this type can be taken back while its task has not started. */
    public boolean revocable() {
        return revocable;
    }

    public boolean allowed() {
        return tier < 3;
    }
}
