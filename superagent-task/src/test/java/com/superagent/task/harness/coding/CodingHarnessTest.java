package com.superagent.task.harness.coding;

import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The coding flow on a real git repository with a scripted agent: it works in its own worktree, the platform's checks
 * (not the agent's word) decide whether it passed, a failing check goes back for a fix, and the result is squash-merged
 * into the branch it started from, one commit per task even when several tasks share that branch.
 */
class CodingHarnessTest {

    @TempDir
    Path tmp;

    /** A scripted agent: writes files into the task's directory the way a real one would, and answers each step. */
    private final class Host implements HarnessHost {
        final List<String> asks = new ArrayList<>();
        final List<String> told = new ArrayList<>();
        final String file;
        final String spec;
        TaskStatus status;
        int developTurns;
        List<Path> roots = List.of();
        HarnessState saved;

        Host(String file) {
            this.file = file;
            this.spec = """
                    ## 目标
                    加一个 %s。

                    ## 不做
                    无

                    ## 验收标准
                    1. %s 存在
                    2. 内容是 hi

                    ## 验证命令
                    ```
                    test -f %s
                    grep -q hi %s
                    ```

                    ## 实现思路
                    新建文件。
                    """.formatted(file, file, file, file);
        }

        @Override
        public Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation c) {
            Path dir = Path.of(task.workspaceDir());
            try {
                switch (label) {
                    case CodingHarness.SPEC -> {
                        return new Turn(spec, BigDecimal.ZERO, false);
                    }
                    case CodingHarness.DEVELOP -> {
                        developTurns++;
                        Files.writeString(dir.resolve(file), "hello\n"); // wrong at first: the check must catch it
                        return new Turn("新建了 " + file + "，自己跑过了，都通过。", BigDecimal.ZERO, false);
                    }
                    case CodingHarness.VERIFY -> {
                        Files.writeString(dir.resolve(file), "hi\n");
                        return new Turn("把内容改成了 hi", BigDecimal.ZERO, false);
                    }
                    default -> {
                        return new Turn("## 验收\n验收 1：满足 | 文件在\n验收 2：满足 | grep 通过\n## 问题\n无\n## 结论\n结论：通过",
                                BigDecimal.ZERO, false);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public Task workIn(Task task, String dir) { return task.withWorkspaceDir(dir); }
        @Override public void progress(Task task, int percent) { }
        @Override public void note(Task task, String text) { }
        @Override public void ask(Task task, String text) { asks.add(text); }
        @Override public void tell(Task task, String text) { told.add(text); }
        @Override public void save(Task task, HarnessState state) { saved = state; }
        @Override public void settle(Task task, TaskStatus s, int progress, String output, String error) { status = s; }
        @Override public Optional<String> connectorToken(String connectorId, String owner) { return Optional.empty(); }
        @Override public Optional<String> connectorSite(String connectorId, String owner) { return Optional.empty(); }
        @Override public List<Path> localRoots() { return roots.isEmpty() ? List.of(tmp.resolve("project")) : roots; }
        @Override public Path workRoot() { return tmp.resolve("work"); }
    }

    @Test
    void aTaskIsCheckedByThePlatformFixedWhenItFailsAndMergedAsOneCommit() throws Exception {
        Path repo = repo();
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0001-aaaa", repo, "加 hello.txt");
        Host host = new Host("hello.txt");
        HarnessState s = harness.start(task, null);
        assertThat(s.mode).isEqualTo("local");
        assertThat(s.delivery).isEqualTo("merge");

        harness.drive(new HarnessRun(host, task, s, null, new Cancellation()));
        // Stops for the user to approve the spec, having read its criteria and commands.
        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(CodingHarness.SPEC);
        assertThat(s.criteria).hasSize(2);
        assertThat(s.verifyCommands).containsExactly("test -f hello.txt", "grep -q hi hello.txt");
        // What the chat shows for approval: the criteria and commands, and the answer that approves them.
        assertThat(host.asks.get(0)).contains("hello.txt 存在", "`grep -q hi hello.txt`", "回复「确认规格」");
        assertThat(Path.of(s.dir)).isNotEqualTo(repo).exists();   // its own worktree, not the user's checkout
        assertThat(git(repo, "status", "--porcelain")).isEmpty();  // the user's checkout is untouched

        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "确认规格", new Cancellation()));

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.told).singleElement().asString().contains("验证第 1 轮没通过", "正在自动修复"); // told the chat
        // The agent said "all passed"; the platform's check said otherwise, and the fix round put it right.
        assertThat(s.rounds).hasSize(2);
        assertThat(s.rounds.get(0).passed()).isFalse();
        assertThat(s.rounds.get(1).passed()).isTrue();
        assertThat(s.criteria).allMatch(HarnessState.Criterion::met);
        // Merged into main as one commit; the worktree and its branch are gone.
        assertThat(Files.readString(repo.resolve("hello.txt"))).isEqualTo("hi\n");
        assertThat(git(repo, "log", "--format=%s", "-1")).isEqualTo("加 hello.txt");
        assertThat(git(repo, "rev-list", "--count", "HEAD")).isEqualTo("2");
        assertThat(Path.of(s.dir)).doesNotExist();
        assertThat(git(repo, "branch", "--list", s.branch)).isEmpty();
        assertThat(s.deliveryResult).isEqualTo(git(repo, "rev-parse", "HEAD"));
    }

    @Test
    void twoTasksOnOneBranchEachWorkApartAndTheSecondRebasesBeforeItMerges() throws Exception {
        Path repo = repo();
        CodingHarness harness = new CodingHarness(60);
        Task a = task("t-0002-aaaa", repo, "加 a.txt");
        Task b = task("t-0003-bbbb", repo, "加 b.txt");
        Host ha = new Host("a.txt");
        Host hb = new Host("b.txt");
        HarnessState sa = harness.start(a, null);
        HarnessState sb = harness.start(b, null);
        harness.drive(new HarnessRun(ha, a, sa, null, new Cancellation()));
        harness.drive(new HarnessRun(hb, b, sb, null, new Cancellation()));
        assertThat(sa.dir).isNotEqualTo(sb.dir);
        assertThat(sa.baseSha).isEqualTo(sb.baseSha);

        harness.drive(new HarnessRun(ha, a.withWorkspaceDir(sa.dir), sa, "确认", new Cancellation()));
        harness.drive(new HarnessRun(hb, b.withWorkspaceDir(sb.dir), sb, "确认", new Cancellation()));

        assertThat(ha.status).isEqualTo(TaskStatus.DONE);
        assertThat(hb.status).isEqualTo(TaskStatus.DONE);
        assertThat(sb.deliveryNote).contains("变基");                    // B started before A merged
        assertThat(git(repo, "log", "--format=%s")).isEqualTo("加 b.txt\n加 a.txt\ninit");
        assertThat(repo.resolve("a.txt")).exists();
        assertThat(repo.resolve("b.txt")).exists();
    }

    @Test
    void aLocalRepositoryOutsideTheAllowedDirectoriesIsRefusedAndTokensGoOnlyToTheirOwnSite() throws Exception {
        Path repo = repo();
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0004-aaaa", repo, "加 x.txt");
        Host host = new Host("x.txt");
        // Checked before the task is even created: the chat hears it, not a task that stops later.
        harness.precheck(repo.toString(), null, null, "tester", host);
        assertThatThrownBy(() -> harness.precheck(repo.toString(), "nope", null, "tester", host)).hasMessageContaining("没有分支 nope");
        assertThatThrownBy(() -> harness.precheck(repo.toString(), null, "pr", "tester", host)).hasMessageContaining("合并回本地分支");
        assertThatThrownBy(() -> harness.precheck("http://git.example.com/a/b.git", null, null, "tester", host))
                .hasMessageContaining("https");
        // Never the server's own ssh keys, never a host the user hasn't authorized.
        assertThatThrownBy(() -> harness.precheck("git@github.com:o/r.git", null, null, "tester", host)).hasMessageContaining("只支持 https");
        assertThatThrownBy(() -> harness.precheck("https://10.0.0.5/team/app.git", null, null, "tester", host))
                .hasMessageContaining("不在其中");
        host.roots = List.of(Files.createDirectories(tmp.resolve("allowed")));
        assertThatThrownBy(() -> harness.precheck(repo.toString(), null, null, "tester", host)).hasMessageContaining("不在允许的代码目录里");
        HarnessState s = harness.start(task, null);
        harness.drive(new HarnessRun(host, task, s, null, new Cancellation()));
        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(host.asks.get(0)).contains("不在允许的代码目录里");
        assertThat(s.dir).isNull();

        // A GitLab token goes only to the site it was verified on, never over http, and GitHub's only to github.com.
        String site = "https://gitlab.example.com";
        assertThat(PullRequests.connectorFor("https://gitlab.example.com/team/app.git", site)).isEqualTo(PullRequests.GITLAB);
        assertThat(PullRequests.connectorFor("https://evil.example.net/team/app.git", site)).isNull();
        assertThat(PullRequests.connectorFor("http://gitlab.example.com/team/app.git", site)).isNull();
        assertThat(PullRequests.connectorFor("https://github.com/o/r.git", null)).isEqualTo(PullRequests.GITHUB);
    }

    private Path repo() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("project"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "demo\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        git(repo, "config", "user.name", "tester");
        git(repo, "config", "user.email", "tester@example.com");
        return repo;
    }

    private static Task task(String id, Path repo, String title) {
        Instant now = Instant.now();
        return new Task(id, title, "按说明做", TaskSource.WEB_CHAT, repo.toString(), null, "coding", TaskStatus.RUNNING, 0,
                BigDecimal.ZERO, null, AgentType.CLAUDE, null, "tester", null, now, now, null, now, null);
    }

    private static String git(Path dir, String... args) throws Exception {
        Git.Result r = new Git(Map.of("PATH", System.getenv("PATH"))).git(dir, args);
        assertThat(r.ok()).as("git %s: %s", String.join(" ", args), r.output()).isTrue();
        return r.out().strip();
    }
}
