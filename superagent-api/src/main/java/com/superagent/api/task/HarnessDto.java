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
        Delivery delivery,
        List<ReviewRound> reviews,
        String stopReason,
        General general,
        List<ConflictRound> conflicts,
        Article article) {

    /**
     * A writing task's record: the brief, sources and claims, the outline and when it was confirmed, the first draft
     * as it was, every platform check and review, the revisions, and the counters that bound the returns.
     */
    public record Article(Brief brief, List<Source> sources, List<Claim> claims, String judgement, String outline,
                          List<String> titles, String thesis, List<Section> sections, boolean outlineApproved,
                          List<Instant> approvals, String approvedBy, String draftFileId, String draftName,
                          String draftSnapshot, List<ArticleCheck> checks, List<ArticleReview> reviews,
                          List<Revision> revisions, int fixRounds, int reviewReturns, int outlineReturns,
                          int researchReturns, int returns, boolean checkWaived, boolean acceptedAsIs,
                          String genre, List<Evidence> evidence) {
    }

    /** One entry of an investigation's evidence log: {@code type} 代码 | 命令 | 数字 | 文档. */
    public record Evidence(String id, String type, String key, String body) {
    }

    public record Brief(String genre, String audience, String knows, String purpose, String thesis, int words,
                        String style, String mustUse, String exclude, String acceptance) {
    }

    public record Source(String id, String title, String ref, String status, String summary) {
    }

    public record Claim(String text, List<String> sources, String status) {
    }

    public record Section(String title, String point, List<String> sources, int words) {
    }

    /** {@code level}: pass | warn | fail. */
    public record Issue(String check, String level, String where, String note) {
    }

    public record ArticleCheck(int round, Instant at, boolean passed, List<Issue> items) {
    }

    public record Score(int dimension, String name, int score, String quote, String advice) {
    }

    public record Fact(String statement, String sources, String verdict, String evidence, boolean adjusted) {
    }

    public record Item(String id, String severity, String type, String text, String where, String from, boolean outline) {
    }

    public record Response(String id, String action, String note) {
    }

    public record ArticleReview(int round, Instant at, String editorModel, String checkerModel, List<Score> scores,
                                int total, List<Item> editor, List<String> titles, List<Fact> facts, List<String> reader,
                                String retelling, Boolean consistent, List<Item> merged, String route, boolean passed) {
    }

    public record Revision(int round, Instant at, String from, List<Item> items, List<Response> responses, boolean changed) {
    }

    /**
     * One go at resolving the conflicts of a rebase before delivery: {@code commit} the task's commit it was on (null: a
     * fix after the rebase), what the developer said it did, and the platform's checks of it (markers, the rebase going
     * on, the verify commands, the review). {@code reason}: why it didn't pass.
     */
    public record ConflictRound(int round, Instant at, String model, String commit, List<String> files, String summary,
                                List<PlatformCheck> checks, String verdict, boolean passed, String reason) {
    }

    /** {@code status}: pending | running | done | failed | waiting (for the user). */
    public record Step(String key, String name, String status, Instant startedAt, Instant finishedAt, String note) {
    }

    /** {@code mode}: local (a worktree off a local repository) or remote (a fresh clone). */
    public record Workspace(String mode, String repo, String baseBranch, String baseSha, String branch, String dir,
                            String delivery) {
    }

    /**
     * One acceptance criterion; {@code evidence} once a check or the review backs it; {@code agentAdded} when the brief
     * was vague and the agent spelled it out.
     */
    public record Criterion(String text, boolean met, String evidence, boolean agentAdded) {
    }

    /**
     * A general task's record: the round under way, what it hands over, each check of the work, and the agent's own
     * closing words of its last round (its summary, before it is delivered).
     */
    public record General(int round, int roundLimit, List<Deliverable> deliverables, List<CheckRound> checks, String reply) {
    }

    /** {@code type}: a file extension (srt, md…) or {@code reply}. */
    public record Deliverable(String name, String type) {
    }

    /** {@code warning}: passed, but with something to look at. */
    public record PlatformCheck(String name, boolean passed, String detail, boolean warning) {
        public PlatformCheck(String name, boolean passed, String detail) {
            this(name, passed, detail, false);
        }
    }

    /** {@code verdict}: PASS | FAIL from the independent checker; null when the platform's checks failed first. */
    public record CheckRound(int round, Instant at, boolean passed, List<PlatformCheck> platform, String verdict,
                             String checker, List<String> issues) {
    }

    public record ChangedFile(String path, int added, int removed) {
    }

    public record Check(String command, int exitCode, long millis, String logTail) {
    }

    /** One run of the checks; {@code fix} says what the agent changed after it, when it failed. */
    public record VerifyRound(int round, Instant at, boolean passed, List<Check> checks, String fix) {
    }

    /**
     * {@code severity}: high | medium | low (a suggestion); {@code state}: open | fixed | ignored | waived;
     * {@code id}: the same issue's name from round to round (AC2: acceptance criterion 2).
     */
    public record Finding(String severity, String text, String where, String state, String id) {
    }

    /** The developer's objection to an issue and the reviewer's ruling: accepted | rejected | null (none yet). */
    public record Rebuttal(String id, String reason, String ruling, String rulingReason) {
    }

    /**
     * One look by the reviewing model: {@code kind} review | accept; {@code blocking} the bug-level issues it left;
     * {@code criteria} (accept) each criterion as it checked it; {@code reverted}: it changed files and they were undone.
     */
    public record ReviewRound(int round, String kind, Instant at, String model, String verdict, boolean passed, int blocking,
                              List<Finding> findings, List<Criterion> criteria, List<Rebuttal> rebuttals, boolean reverted) {
    }

    /** {@code result}: the merged commit (local) or the pull request's address (remote), once delivered. */
    public record Delivery(String mode, String result, String note) {
    }
}
