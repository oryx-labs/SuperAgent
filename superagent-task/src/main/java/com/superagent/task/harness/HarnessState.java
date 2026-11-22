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
    public record Criterion(String text, boolean met, String evidence, boolean agentAdded, String kind) {
        /** Why an unmet criterion failed: the hand-over note lacks it, rather than the code. */
        public static final String NOTE = "note";

        public Criterion(String text, boolean met, String evidence) {
            this(text, met, evidence, false, null);
        }

        public Criterion(String text, boolean met, String evidence, boolean agentAdded) {
            this(text, met, evidence, agentAdded, null);
        }

        public Criterion judged(boolean met, String evidence) {
            return new Criterion(text, met, evidence, agentAdded, kind);
        }

        public boolean noteOnly() {
            return NOTE.equals(kind);
        }
    }

    /** What the task is to hand over; {@code type} is a file extension (srt, md…) or {@code reply} for an answer. */
    public record Deliverable(String name, String type) {
    }

    /** One of the platform's own checks on the work (no model involved). */
    /** {@code warning}: passed, but with something to look at (a warning never fails the check). Absent in older records. */
    public record PlatformCheck(String name, boolean passed, String detail, boolean warning) {
        public PlatformCheck(String name, boolean passed, String detail) {
            this(name, passed, detail, false);
        }
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
    /** The step that already got a fresh conversation after an API client error (a second one asks the user). */
    public String rerunStep;

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
    /** The developer's hand-over note (what it did per criterion, and the proof), as the acceptor reads it. */
    public String handoff;
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

    // --- the writing flow's record (null for every other flow) ---
    public Article article;

    /**
     * A writing task's record: the brief, its sources and claims, the outline and who confirmed it, the first draft as
     * it was (Files keeps only the latest), every platform check and review, and the counters that bound the returns.
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Article {

        public record Brief(String genre, String audience, String knows, String purpose, String thesis, int words,
                            String style, String mustUse, String exclude, String acceptance) {
        }

        /** {@code status}: 已读 | 读不到 | 不用. */
        public record Source(String id, String title, String ref, String status, String summary) {
        }

        /** {@code status}: 已证实 | 推断 | 未知. */
        public record Claim(String text, List<String> sources, String status) {
        }

        public record Section(String title, String point, List<String> sources, int words) {
        }

        /** One platform check: {@code level} pass | warn | fail. */
        public record Issue(String check, String level, String where, String note) {
            public boolean failed() {
                return "fail".equals(level);
            }
        }

        public record CheckRound(int round, Instant at, boolean passed, List<Issue> items) {
        }

        public record Score(int dimension, String name, int score, String quote, String advice) {
        }

        /** {@code verdict}: 成立 | 不成立 | 无法核实; {@code adjusted}: the platform didn't find its evidence and changed it. */
        public record Fact(String statement, String sources, String verdict, String evidence, boolean adjusted) {
        }

        /**
         * One entry of the merged revision list: {@code severity} 严重 | 一般 | 建议 (only a suggestion never sends the
         * draft back); {@code type} 结构 | 论证 | 表达 | 规范 | 事实 | 证据 | 核对 | 用户; {@code outline}: the editor
         * said the outline itself has to change.
         */
        public record Item(String id, String severity, String type, String text, String where, String from, boolean outline) {
            public boolean blocking() {
                return !"建议".equals(severity);
            }

            public Item withId(String i) {
                return new Item(i, severity, type, text, where, from, outline);
            }
        }

        /**
         * One entry of an investigation's evidence log: {@code type} 代码 | 命令 | 数字 | 文档; {@code key} what it is
         * (a {@code path:line}, the command, the figure, the document's title, version and link); {@code body} the rest.
         */
        public record Evidence(String id, String type, String key, String body) {
        }

        /** {@code action}: 已改 | 不改 (with the author's reason). */
        public record Response(String id, String action, String note) {
        }

        /** {@code route}: pass | revise | outline | research | stop. */
        public record Review(int round, Instant at, String editorModel, String checkerModel, List<Score> scores,
                             int total, List<Item> editor, List<String> titles, List<Fact> facts, List<String> reader,
                             String retelling, Boolean consistent, List<Item> merged, String route, boolean passed) {
        }

        /** {@code from}: check | review | user. */
        public record Revision(int round, Instant at, String from, List<Item> items, List<Response> responses,
                               boolean changed) {
        }

        public Brief brief;
        /** The genre asked for when the task was made (a pack's name or id); null: the original, pack-less flow. */
        public String genre;
        /** The genre pack the task runs by, with the text the author is given; null while the original flow runs. */
        public com.superagent.task.harness.article.GenrePack pack;
        public String guide;
        public List<Evidence> evidence = new ArrayList<>();
        public List<Source> sources = new ArrayList<>();
        public List<Claim> claims = new ArrayList<>();
        public String judgement;
        public String outline;
        public List<String> titles = new ArrayList<>();
        public String thesis;
        public List<Section> sections = new ArrayList<>();
        public boolean outlineApproved;
        /** When the user confirmed the outline, each time. */
        public List<Instant> approvals = new ArrayList<>();
        public String approvedBy;
        public String draftFileId;
        public String draftName;
        public String draftSnapshot;
        public String draftHash;
        public List<CheckRound> checks = new ArrayList<>();
        public List<Review> reviews = new ArrayList<>();
        public List<Revision> revisions = new ArrayList<>();
        /** What the next revision has to address, and where it came from (check | review | user). */
        public List<Item> pending = new ArrayList<>();
        public String revisionFrom;
        /** Numbers in the draft the platform couldn't find in the sources, for the fact-checker. */
        public List<String> untraced = new ArrayList<>();
        /** The step to run next (the step bar shows where returns went; this is where the run is). */
        public String next;
        /** What a reply from the user answers while the task waits: ask | brief | sources | outline | check | review. */
        public String waitKind;
        public String hint;
        public int fixRounds;
        public int reviewReturns;
        public int outlineReturns;
        public int researchReturns;
        /** The user said to go on with failing checks, or to deliver as it stands. */
        public boolean checkWaived;
        public boolean acceptedAsIs;
    }

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
