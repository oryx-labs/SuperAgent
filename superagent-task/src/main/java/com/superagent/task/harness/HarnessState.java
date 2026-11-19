package com.superagent.task.harness;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a harness knows about one task, saved after every step so a restart (or a reply from the user) picks the
 * run up exactly where it stopped. The step list is the flow's own; the rest is what the coding flow records (other
 * flows leave it empty and keep their own artifacts in {@link #data}).
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class HarnessState {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public enum Status { PENDING, RUNNING, DONE, FAILED, WAITING }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Step {
        public String key;
        public String name;
        public Status status = Status.PENDING;
        public Instant startedAt;
        public Instant finishedAt;
        public String note;

        Step() {
        }

        public Step(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    /** {@code agentAdded}: the brief was too vague, so the agent spelled this one out itself. */
    public record Criterion(String text, boolean met, String evidence, boolean agentAdded) {
        public Criterion(String text, boolean met, String evidence) {
            this(text, met, evidence, false);
        }

        public Criterion judged(boolean met, String evidence) {
            return new Criterion(text, met, evidence, agentAdded);
        }
    }

    /** What the task is to hand over; {@code type} is a file extension (srt, md…) or {@code reply} for an answer. */
    public record Deliverable(String name, String type) {
    }

    /** One of the platform's own checks on the work (no model involved). */
    public record PlatformCheck(String name, boolean passed, String detail) {
    }

    /**
     * One check of a general task's work: the platform's checks, then (only when those passed) the independent
     * checker's verdict ({@code PASS}/{@code FAIL}, null when it didn't run) and the model it ran on.
     */
    public record CheckRound(int round, Instant at, boolean passed, List<PlatformCheck> platform, String verdict,
                             String checker, List<String> issues) {
    }

    public record ChangedFile(String path, int added, int removed) {
    }

    public record Check(String command, int exitCode, long millis, String logTail) {
    }

    public record Round(int round, Instant at, boolean passed, List<Check> checks, String fix) {
    }

    /**
     * {@code severity}: high | medium (bug-level: sent back for a fix) | low (a suggestion, never blocks);
     * {@code state}: open | fixed | ignored | waived (the reviewer accepted the developer's case against it);
     * {@code id}: what the same issue is called from one round to the next ({@code 3}, or {@code AC2} for criterion 2).
     */
    public record Finding(String severity, String text, String where, String state, String id) {
        public Finding(String severity, String text, String where, String state) {
            this(severity, text, where, state, null);
        }

        public Finding withState(String s) {
            return new Finding(severity, text, where, s, id);
        }

        public Finding withId(String i) {
            return new Finding(severity, text, where, state, i);
        }

        /** A bug-level issue still standing: it sends the work back. */
        public boolean blocking() {
            return !"low".equals(severity) && "open".equals(state);
        }
    }

    /** The developer's case against an issue, and the reviewer's ruling on it ({@code accepted} | {@code rejected}). */
    public record Rebuttal(String id, String reason, String ruling, String rulingReason) {
    }

    /**
     * One look by the reviewing model: {@code kind} review (the code) or accept (the spec's criteria, one by one).
     * {@code verdict} PASS | FAIL as it wrote it (FAIL when it wrote none); {@code blocking} the bug-level issues left;
     * {@code reverted}: it changed files, and the platform undid that.
     */
    public record ReviewRound(int round, String kind, Instant at, String model, String verdict, boolean passed,
                              int blocking, List<Finding> findings, List<Criterion> criteria, List<Rebuttal> rebuttals,
                              boolean reverted) {
    }

    /**
     * One go at a conflicted rebase: the developer resolved {@code commit}'s conflicts in {@code files} (or fixed what the
     * checks found after), and the platform judged it: no markers, the rebase went on, the verify commands, a review of
     * just the resolution. {@code reason}: why it didn't pass.
     */
    public record ConflictRound(int round, Instant at, String model, String commit, List<String> files, String summary,
                                List<PlatformCheck> checks, String verdict, boolean passed, String reason) {
    }

    public String harness;
    public List<Step> steps = new ArrayList<>();
    /** The step a reply from the user answers, while the task waits on one. */
    public String waitingOn;
    /** The step and question the user was last asked about: asking the same again doesn't notify them again. */
    public String askedKey;

    // --- the coding flow's record ---
    public String mode;          // local | remote
    public String repo;          // the path or URL as given
    public String repoRoot;      // a local repository's top directory
    public String baseBranch;
    public String baseSha;
    public String branch;        // agent/<task>
    public String dir;           // where the agent works: the worktree or the clone
    public String delivery;      // merge | pr
    public String conversation;  // the developer's conversation (the reviewer always starts a new one)
    public boolean conversationStarted;
    public String spec;
    public boolean specApproved;
    public List<Criterion> criteria = new ArrayList<>();
    public List<String> verifyCommands = new ArrayList<>();
    public List<ChangedFile> files = new ArrayList<>();
    public Map<String, String> patches = new LinkedHashMap<>();
    public List<Round> rounds = new ArrayList<>();
    public int fixBudget = 3;
    public List<Finding> findings = new ArrayList<>();
    public int reviewRounds;
    // The review loop: review → accept, on another model; issues go back to the developer until it passes or stops.
    public List<ReviewRound> reviews = new ArrayList<>();
    public int loopRound;
    /** Where the stop rules start counting (a reply from the user after a stop starts them over). */
    public int loopBase;
    public double spendBase;
    public String reviewerModel;
    public String reviewerCli;
    public int nextFindingId = 1;
    public List<String> waived = new ArrayList<>();
    /** The developer's case against issues of the last round, for the next review to rule on. */
    public List<Rebuttal> pendingRebuttals = new ArrayList<>();
    /** Why the loop stopped for the user (null while it runs, or once it passed). */
    public String stopReason;
    public String deliveryResult;
    public String deliveryNote;
    /** Resolving a conflicted rebase before delivery: the commit it is rebasing onto while under way, each go at it. */
    public String rebaseOnto;
    /** The task's branch and base before the resolve step rebased it, to go back to if it gives up (null otherwise). */
    public String preRebaseHead;
    public String preRebaseBase;
    /** How often delivery went back to resolving since the user last said go on. */
    public int resolveTimes;
    public List<ConflictRound> conflicts = new ArrayList<>();
    /** What the user said about the conflicts ("以 main 为准"…), for every go at resolving them until delivery. */
    public String conflictHint;

    // --- the general flow's record ---
    /** The execute → check round under way; a failed check sends the work back for the next one. */
    public int round = 1;
    /** The last round that may run before the task stops for the user. */
    public int roundLimit = 3;
    public List<Deliverable> deliverables = new ArrayList<>();
    public List<CheckRound> checks = new ArrayList<>();
    /** The agent's final reply of its last execute turn: what is checked when there are no files. */
    public String reply;
    /** What the user said for the next execute round (a reply to a stop, or a follow-up after delivery). */
    public String hint;

    /** Anything another flow wants to keep. */
    public Map<String, String> data = new LinkedHashMap<>();

    public Step step(String key) {
        return steps.stream().filter(s -> s.key.equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No step " + key + " in " + harness));
    }

    /** The first step not done yet; null once every step is. */
    public Step current() {
        return steps.stream().filter(s -> s.status != Status.DONE).findFirst().orElse(null);
    }

    public boolean finished() {
        return current() == null;
    }

    public int indexOf(Step s) {
        return steps.indexOf(s);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static HarnessState fromJson(String json) {
        try {
            return JSON.readValue(json, HarnessState.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable harness state: " + e.getMessage(), e);
        }
    }

    /** Whether stored phase JSON is a harness state (the coding flow before harnesses kept a plain list). */
    public static boolean isState(String json) {
        return json != null && json.stripLeading().startsWith("{");
    }
}
