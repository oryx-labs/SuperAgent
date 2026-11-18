package com.superagent.api.task;

import java.time.Instant;
import java.util.List;

/**
 * A task run by a built-in harness: a fixed sequence of steps, each judged by the platform (commands it runs itself,
 * an independent review), not by what the agent says about its own work. {@code harness} names the flow
 * ({@code coding}); the workspace fields are a coding task's.
 */
public record HarnessDto(
        String harness,
        List<Step> steps,
        Workspace workspace,
        String spec,
        boolean specApproved,
        List<Criterion> criteria,
        List<String> verifyCommands,
        List<ChangedFile> files,
        List<VerifyRound> rounds,
        List<Finding> findings,
        Delivery delivery) {

    /** {@code status}: pending | running | done | failed | waiting (for the user). */
    public record Step(String key, String name, String status, Instant startedAt, Instant finishedAt, String note) {
    }

    /** {@code mode}: local (a worktree off a local repository) or remote (a fresh clone). */
    public record Workspace(String mode, String repo, String baseBranch, String baseSha, String branch, String dir,
                            String delivery) {
    }

    /** One acceptance criterion from the spec; {@code evidence} once a check or the review backs it. */
    public record Criterion(String text, boolean met, String evidence) {
    }

    public record ChangedFile(String path, int added, int removed) {
    }

    public record Check(String command, int exitCode, long millis, String logTail) {
    }

    /** One run of the checks; {@code fix} says what the agent changed after it, when it failed. */
    public record VerifyRound(int round, Instant at, boolean passed, List<Check> checks, String fix) {
    }

    /** {@code severity}: high | medium | low; {@code state}: open | fixed | ignored. */
    public record Finding(String severity, String text, String where, String state) {
    }

    /** {@code result}: the merged commit (local) or the pull request's address (remote), once delivered. */
    public record Delivery(String mode, String result, String note) {
    }
}
