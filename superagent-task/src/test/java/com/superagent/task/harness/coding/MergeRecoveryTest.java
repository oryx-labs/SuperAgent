package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** What a restart in the middle of a local delivery leaves behind, and what is (and isn't) undone on the next start. */
// MergeGuard counts every merge in this JVM, and these tests assert on that count: they run one at a time,
// and never alongside another class that merges.
@ResourceLock(MergeRecoveryTest.MERGES)
@Execution(ExecutionMode.SAME_THREAD)
class MergeRecoveryTest {

    static final String MERGES = "MergeGuard";

    @TempDir
    Path tmp;

    private final Git git = new Git(Map.of());

    @Test
    void aHalfDoneSquashMergeOfTheTasksBranchIsUndoneAndMergedAgainAsOneCommit() throws Exception {
        HarnessState s = taskWithCommit("t1");
        Path root = Path.of(s.repoRoot);
        sh(root, "git", "merge", "--squash", s.branch); // the old process stopped here, before committing
        assertThat(Files.exists(root.resolve(".git/SQUASH_MSG"))).isTrue();

        assertThat(new MergeRecovery(git).repair(s)).isEmpty();

        assertThat(sh(root, "git", "status", "--porcelain")).isBlank();
        assertThat(Files.exists(root.resolve(".git/SQUASH_MSG"))).isFalse();
        String before = sh(root, "git", "rev-list", "--count", "HEAD").strip();
        Workspace.Merge m = new Workspace(git).mergeLocal(s, "deliver t1", () -> true);
        assertThat(m.problem()).isNull();
        assertThat(Integer.parseInt(sh(root, "git", "rev-list", "--count", "HEAD").strip())).isEqualTo(Integer.parseInt(before) + 1);
        assertThat(Files.readString(root.resolve("feature.txt"))).isEqualTo("t1\n");
    }

    @Test
    void aRebaseStoppedInTheTasksWorktreeIsAborted() throws Exception {
        HarnessState s = taskWithCommit("t2");
        Path root = Path.of(s.repoRoot);
        Files.writeString(root.resolve("feature.txt"), "main\n"); // main moves on, touching the same file
        sh(root, "git", "add", "-A");
        sh(root, "git", "commit", "-qm", "main change");
        Path wt = Path.of(s.dir);
        new ProcessBuilder("git", "rebase", "main").directory(wt.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(); // conflicts: stops midway
        assertThat(Files.isDirectory(Path.of(sh(wt, "git", "rev-parse", "--absolute-git-dir").strip(), "rebase-merge"))).isTrue();

        assertThat(new MergeRecovery(git).repair(s)).isEmpty();

        assertThat(Files.exists(Path.of(sh(wt, "git", "rev-parse", "--absolute-git-dir").strip(), "rebase-merge"))).isFalse();
        assertThat(sh(wt, "git", "rev-parse", "--abbrev-ref", "HEAD").strip()).isEqualTo(s.branch);
    }

    @Test
    void someoneElsesHalfDoneMergeIsLeftAloneAndReported() throws Exception {
        HarnessState s = taskWithCommit("t3");
        Path root = Path.of(s.repoRoot);
        sh(root, "git", "checkout", "-qb", "mine");
        Files.writeString(root.resolve("other.txt"), "mine\n");
        sh(root, "git", "add", "-A");
        sh(root, "git", "commit", "-qm", "my own work");
        sh(root, "git", "checkout", "-q", "main");
        sh(root, "git", "merge", "--squash", "mine");
        String status = sh(root, "git", "status", "--porcelain");

        assertThat(new MergeRecovery(git).repair(s)).singleElement().asString().contains("不是这个任务做的");

        assertThat(sh(root, "git", "status", "--porcelain")).isEqualTo(status);
        assertThat(Files.exists(root.resolve(".git/SQUASH_MSG"))).isTrue();
    }

    @Test
    void shutdownWaitsForAMergeInProgress() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HarnessState s = taskWithCommit("t4");
        Path root = Path.of(s.repoRoot);
        Files.writeString(root.resolve("base.txt"), "moved\n"); // main moved: the merge rebases, then rechecks
        sh(root, "git", "add", "-A");
        sh(root, "git", "commit", "-qm", "main moved");
        Thread merging = new Thread(() -> new Workspace(git).mergeLocal(s, "deliver t4", () -> {
            inside.countDown();
            try {
                return release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }));
        merging.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(MergeGuard.awaitIdle(Duration.ofMillis(300))).isFalse(); // still merging: shutdown keeps waiting
        AtomicBoolean idle = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                idle.set(MergeGuard.awaitIdle(Duration.ofSeconds(10)));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        release.countDown();
        waiter.join(10_000);
        merging.join(10_000);
        assertThat(idle).isTrue();
        assertThat(Files.readString(root.resolve("feature.txt"))).isEqualTo("t4\n"); // the merge went through
    }

    /** A local repository on main, and the task's worktree on agent/{id} with one commit. */
    private HarnessState taskWithCommit(String id) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("repo-" + id));
        sh(root, "git", "init", "-q", "-b", "main");
        sh(root, "git", "config", "user.name", "t");
        sh(root, "git", "config", "user.email", "t@t");
        Files.writeString(root.resolve("base.txt"), "base\n");
        sh(root, "git", "add", "-A");
        sh(root, "git", "commit", "-qm", "init");
        String branch = "agent/" + id;
        Path wt = tmp.resolve("wt-" + id);
        sh(root, "git", "worktree", "add", "-q", "-b", branch, wt.toString());
        Files.writeString(wt.resolve("feature.txt"), id + "\n");
        sh(wt, "git", "add", "-A");
        sh(wt, "git", "commit", "-qm", "task " + id);

        HarnessState s = new HarnessState();
        s.mode = "local";
        s.repo = root.toString();
        s.repoRoot = root.toRealPath().toString();
        s.baseBranch = "main";
        s.baseSha = sh(root, "git", "rev-parse", "main").strip();
        s.branch = branch;
        s.dir = wt.toString();
        s.delivery = "merge";
        return s;
    }

    private static String sh(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(String.join(" ", cmd) + ": " + out).isZero();
        return out;
    }
}
