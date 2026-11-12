package com.superagent.core.task;

import java.time.Instant;

/**
 * One decision about which model runs a task, kept so anyone can see why it ran on what it ran on (and what that
 * cost). A task has one per run: the first before it starts, then one for each time it moved to a stronger model.
 *
 * @param by         who decided: {@link #BY_JUDGE}, {@link #BY_USER}, {@link #BY_FALLBACK}, {@link #BY_ESCALATION}, or
 *                   {@link #BY_DEFAULT} (tiers off: the creator's model or the configured default, nothing judged), or
 *                   {@link #BY_FIXED} (the admin holds tasks to a set of models: no judging, cheapest first, the next
 *                   dearer one after a failure)
 * @param model      what it runs on; null only for {@link #BY_FIXED} when none of the fixed models is left to try, and
 *                   the task fails for the reason given
 * @param difficulty easy | medium | hard, as judged (null when nobody judged it)
 * @param judge      the model that judged it (null when none did)
 * @param candidates the models it could have run on, one per line, with tier and price
 * @param reasoning  why this one, in words
 */
public record TaskModelChoice(String taskId, int seq, String by, String difficulty, String tier, String model,
                              String cli, String judge, String candidates, String reasoning, Instant createdAt) {

    public static final String BY_JUDGE = "judge";
    public static final String BY_USER = "user";
    public static final String BY_FALLBACK = "fallback";
    public static final String BY_ESCALATION = "escalation";
    public static final String BY_DEFAULT = "default";
    public static final String BY_FIXED = "fixed";

    /** Nothing left to run it on: the task is to fail, not to fall back to some other model. */
    public boolean refused() {
        return model == null && BY_FIXED.equals(by);
    }

    public TaskModelChoice withSeq(int n) {
        return new TaskModelChoice(taskId, n, by, difficulty, tier, model, cli, judge, candidates, reasoning, createdAt);
    }
}
