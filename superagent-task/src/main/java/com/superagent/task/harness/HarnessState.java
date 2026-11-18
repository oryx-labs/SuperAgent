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

    public record Criterion(String text, boolean met, String evidence) {
    }

    public record ChangedFile(String path, int added, int removed) {
    }

    public record Check(String command, int exitCode, long millis, String logTail) {
    }

    public record Round(int round, Instant at, boolean passed, List<Check> checks, String fix) {
    }

    public record Finding(String severity, String text, String where, String state) {
        public Finding withState(String s) {
            return new Finding(severity, text, where, s);
        }
    }

    public String harness;
    public List<Step> steps = new ArrayList<>();
    /** The step a reply from the user answers, while the task waits on one. */
    public String waitingOn;

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
    public String deliveryResult;
    public String deliveryNote;

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
