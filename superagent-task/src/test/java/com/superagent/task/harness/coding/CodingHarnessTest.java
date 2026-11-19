package com.superagent.task.harness.coding;

import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskModelChoice;
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

    /** One turn as the agent got it. {@code model}: what a review ran on (null: the task's own). */
    record Call(String label, String conversation, boolean first, String model, String prompt) {
    }

    /**
     * A scripted agent: writes files into the task's directory the way a real one would, and answers each step; the
     * reviewer's and acceptor's answers come from queues (a pass once they run out).
     */
    private final class Host implements HarnessHost {
        static final String REVIEW_PASS = "## 问题\n无\n## 结论\nVERDICT: PASS";
        static final String ACCEPT_PASS = "1. ✅ | 文件在\n2. ✅ | grep 通过\n\nVERDICT: PASS";
        final List<String> asks = new ArrayList<>();
        final List<String> told = new ArrayList<>();
        final List<Call> calls = new ArrayList<>();
        final List<TaskModelChoice> recorded = new ArrayList<>();
        final java.util.ArrayDeque<String> reviews = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<String> accepts = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<String> fixReplies = new java.util.ArrayDeque<>();
        /** How the developer resolves conflicts, one per go (then: both sides kept). */
        final java.util.ArrayDeque<java.util.function.Function<Path, String>> resolves = new java.util.ArrayDeque<>();
        final List<Boolean> repeats = new ArrayList<>();
        int idleDevelops; // develop turns that change nothing
        final String file;
        final String spec;
        TaskStatus status;
        String output;
        int developTurns;
        boolean scribble; // the first review edits files, as a reviewer mustn't
        String reviewerModel;
        BigDecimal spent = BigDecimal.ZERO;
        ReviewLimits limits = new ReviewLimits(8, 30);
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
            calls.add(new Call(label, conversation, first, null, prompt));
            return answer(task, label);
        }

        @Override
        public Turn reviewTurn(Task task, Model model, String prompt, String conversation, String label, Cancellation c) {
            calls.add(new Call(label, conversation, true, model == null ? null : model.model(), prompt));
            return answer(task, label);
        }

        private Turn answer(Task task, String label) {
            Path dir = Path.of(task.workspaceDir());
            try {
                switch (label) {
                    case CodingHarness.SPEC -> {
                        return new Turn(spec, BigDecimal.ZERO, false);
                    }
                    case CodingHarness.DEVELOP -> {
                        if (idleDevelops > 0) {
                            idleDevelops--;
                            return new Turn("看了一下，没什么要改的", BigDecimal.ZERO, false);
                        }
                        developTurns++;
                        if (developTurns == 1) {
                            Files.writeString(dir.resolve(file), "hello\n"); // wrong at first: the check must catch it
                            return new Turn("新建了 " + file + "，自己跑过了，都通过。", BigDecimal.ZERO, false);
                        }
                        Files.writeString(dir.resolve("fix-" + developTurns + ".txt"), "fixed\n");
                        String reply = fixReplies.isEmpty() ? "问题都修了" : fixReplies.poll();
                        return new Turn(reply, BigDecimal.ZERO, false);
                    }
                    case CodingHarness.VERIFY -> {
                        Files.writeString(dir.resolve(file), "hi\n");
                        return new Turn("把内容改成了 hi", BigDecimal.ZERO, false);
                    }
                    case CodingHarness.REVIEW -> {
                        if (scribble) {
                            scribble = false;
                            Files.writeString(dir.resolve(file), "reviewer was here\n");
                            Files.writeString(dir.resolve("notes.md"), "scratch\n");
                        }
                        return new Turn(reviews.isEmpty() ? REVIEW_PASS : reviews.poll(), BigDecimal.ZERO, false);
                    }
                    case CodingHarness.RESOLVE -> {
                        java.util.function.Function<Path, String> how = resolves.isEmpty() ? CodingHarnessTest::keepBoth : resolves.poll();
                        return new Turn(how.apply(dir), BigDecimal.ZERO, false);
                    }
                    case CodingHarness.RESOLVE_REVIEW -> {
                        return new Turn(REVIEW_PASS, BigDecimal.ZERO, false);
                    }
                    default -> {
                        return new Turn(accepts.isEmpty() ? ACCEPT_PASS : accepts.poll(), BigDecimal.ZERO, false);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Optional<TaskModelChoice> reviewer(Task task) {
            return Optional.ofNullable(reviewerModel).map(m -> new TaskModelChoice(task.id(), 0, TaskModelChoice.BY_REVIEWER,
                    null, null, m, "codex", null, "- " + m, "其他厂商里最强的 " + m, Instant.now()));
        }

        List<Call> calls(String label) {
            return calls.stream().filter(x -> x.label().equals(label)).toList();
        }

        @Override public void recordChoice(TaskModelChoice choice) { recorded.add(choice); }
        @Override public BigDecimal spend(Task task) { return spent; }
        @Override public ReviewLimits reviewLimits() { return limits; }
        @Override public Task workIn(Task task, String dir) { return task.withWorkspaceDir(dir); }
        @Override public void progress(Task task, int percent) { }
        @Override public void note(Task task, String text) { }
        @Override public void ask(Task task, String text) { asks.add(text); }
        @Override public void tell(Task task, String text) { told.add(text); }
        @Override public void save(Task task, HarnessState state) { saved = state; }
        @Override public void settle(Task task, TaskStatus s, int progress, String output, String error) {
            status = s;
            this.output = output;
        }
        @Override public void settle(Task task, TaskStatus s, int progress, String output, String error, boolean repeated) {
            repeats.add(repeated);
            settle(task, s, progress, output, error);
        }
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
        // Reviewed, then accepted criterion by criterion, before delivery.
        assertThat(s.reviews).extracting(HarnessState.ReviewRound::kind).containsExactly("review", "accept");
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


    // --- the review loop ---

    /** A task through its spec, approved: the loop then runs to delivery or a stop. */
    private HarnessState approved(CodingHarness harness, Task task, Host host) {
        HarnessState s = harness.start(task, null);
        harness.drive(new HarnessRun(host, task, s, null, new Cancellation()));
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "确认规格", new Cancellation()));
        return s;
    }

    @Test
    void anIssueGoesBackToTheDeveloperInItsOwnConversationAndIsVerifiedReviewedAndAcceptedAgain() throws Exception {
        Path repo = repo();
        Task task = task("t-0101-aaaa", repo, "加 r.txt");
        Host host = new Host("r.txt");
        host.reviewerModel = "gpt-5";
        host.scribble = true;
        host.reviews.add("## 问题\n- [严重] 没有处理空输入 | r.txt:1\n- [建议] 可以加注释 | r.txt:1\n## 结论\nVERDICT: FAIL");
        HarnessState s = approved(new CodingHarness(60), task, host);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.reviews).extracting(HarnessState.ReviewRound::round, HarnessState.ReviewRound::kind,
                        HarnessState.ReviewRound::passed, HarnessState.ReviewRound::blocking)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "review", false, 1),
                        org.assertj.core.groups.Tuple.tuple(2, "review", true, 0),
                        org.assertj.core.groups.Tuple.tuple(2, "accept", true, 0));
        // The reviewer: another model, a new conversation every time, never the developer's.
        List<Call> looks = new ArrayList<>(host.calls(CodingHarness.REVIEW));
        looks.addAll(host.calls(CodingHarness.ACCEPT));
        assertThat(looks).hasSize(3).allMatch(c -> "gpt-5".equals(c.model()) && c.first() && !c.conversation().equals(s.conversation));
        assertThat(looks.stream().map(Call::conversation).distinct()).hasSize(3);
        assertThat(host.recorded).singleElement().extracting(TaskModelChoice::model).isEqualTo("gpt-5");
        // The fix: the developer's own conversation, carrying on, with only the bug-level issue.
        Call fix = host.calls(CodingHarness.DEVELOP).get(1);
        assertThat(fix.conversation()).isEqualTo(s.conversation);
        assertThat(fix.first()).isFalse();
        assertThat(fix.prompt()).contains("没有处理空输入").doesNotContain("可以加注释");
        // Verified again after the fix; the second review was told what went back.
        assertThat(s.rounds).hasSize(3);
        assertThat(looks.get(1).prompt()).contains("#1 没有处理空输入");
        // The reviewer only reads: what it wrote is gone, and the round says so.
        assertThat(s.reviews.get(0).reverted()).isTrue();
        assertThat(repo.resolve("notes.md")).doesNotExist();
        assertThat(Files.readString(repo.resolve("r.txt"))).isEqualTo("hi\n");
        assertThat(host.output).contains("审查和验收", "第 1 轮审查（gpt-5）：1 个问题交回修复");
    }

    @Test
    void acceptanceChecksEachCriterionAndAMissingVerdictIsAFail() throws Exception {
        Path repo = repo();
        Task task = task("t-0102-aaaa", repo, "加 a.txt");
        Host host = new Host("a.txt");
        host.accepts.add("1. ✅ | 文件在\n2. ❌ | 内容不是 hi\n\nVERDICT: PASS"); // a ❌ fails, whatever the verdict
        host.accepts.add("1. ✅ | 文件在\n2. ✅ | grep 通过");                   // no VERDICT line: a fail
        HarnessState s = approved(new CodingHarness(60), task, host);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        List<HarnessState.ReviewRound> accepts = s.reviews.stream().filter(r -> r.kind().equals("accept")).toList();
        assertThat(accepts).extracting(HarnessState.ReviewRound::verdict, HarnessState.ReviewRound::passed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PASS", false),
                        org.assertj.core.groups.Tuple.tuple("FAIL", false), org.assertj.core.groups.Tuple.tuple("PASS", true));
        assertThat(accepts.get(0).findings()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("AC2");
            assertThat(f.text()).contains("内容是 hi");
        });
        assertThat(host.calls(CodingHarness.DEVELOP).get(1).prompt()).contains("验收 2", "内容不是 hi");
        assertThat(s.criteria).allMatch(HarnessState.Criterion::met);

        // A criterion the answer skipped is not met.
        var skipped = CodingPrompts.parseAccept("1. ✅ | 有\nVERDICT: PASS", s.criteria);
        assertThat(skipped.criteria()).extracting(HarnessState.Criterion::met).containsExactly(true, false);
    }

    @Test
    void suggestionsAreListedButNeverSendTheWorkBack() throws Exception {
        Path repo = repo();
        Host host = new Host("s.txt");
        host.reviews.add("## 问题\n- [建议] 变量名可以更清楚 | s.txt:1\n## 结论\nVERDICT: PASS");
        HarnessState s = approved(new CodingHarness(60), task("t-0103-aaaa", repo, "加 s.txt"), host);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.developTurns).isEqualTo(1);
        assertThat(s.reviews.get(0).passed()).isTrue();
        assertThat(host.output).contains("变量名可以更清楚");
        // No other model to pick: the developer's own, but still in a conversation of its own.
        assertThat(host.calls(CodingHarness.REVIEW)).singleElement()
                .satisfies(c -> assertThat(c.conversation()).isNotEqualTo(s.conversation));
    }

    @Test
    void theDeveloperMayObjectAndTheReviewerRules() throws Exception {
        Path repo = repo();
        Host host = new Host("o.txt");
        host.reviews.add("## 问题\n- [一般] 应该处理空文件 | o.txt:1\nVERDICT: FAIL");
        host.fixReplies.add("问题 #1：不同意 | 规格没要求处理空文件");
        host.reviews.add("## 裁定\n裁定 #1：驳回 | 空文件会让 grep 失败\n## 问题\n- [一般] #1 应该处理空文件 | o.txt:1\nVERDICT: FAIL");
        host.fixReplies.add("问题 #1：不同意 | 验证命令里已经保证文件非空");
        host.reviews.add("裁定 #1：接受 | 说得对\n- [一般] #1 应该处理空文件 | o.txt:1\nVERDICT: FAIL");
        HarnessState s = approved(new CodingHarness(60), task("t-0104-aaaa", repo, "加 o.txt"), host);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.calls(CodingHarness.REVIEW).get(1).prompt()).contains("开发不同意", "规格没要求处理空文件");
        List<HarnessState.ReviewRound> reviews = s.reviews.stream().filter(r -> r.kind().equals("review")).toList();
        assertThat(reviews.get(1).rebuttals()).singleElement().extracting(HarnessState.Rebuttal::ruling).isEqualTo("rejected");
        assertThat(reviews.get(1).passed()).isFalse();
        assertThat(reviews.get(2).rebuttals()).singleElement().extracting(HarnessState.Rebuttal::ruling).isEqualTo("accepted");
        assertThat(reviews.get(2).findings()).singleElement().extracting(HarnessState.Finding::state).isEqualTo("waived");
        assertThat(reviews.get(2).passed()).isTrue();
    }

    @Test
    void anIssueThatOutlivesTwoFixesStopsTheLoopAndTheUserMayDeliver() throws Exception {
        Path repo = repo();
        Host host = new Host("p.txt");
        for (int i = 0; i < 3; i++) {
            host.reviews.add("- [严重] 读取文件时没有关闭流 | p.txt:" + (i + 1) + "\nVERDICT: FAIL"); // no id kept: matched by place and words
        }
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0105-aaaa", repo, "加 p.txt");
        HarnessState s = approved(harness, task, host);

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(CodingHarness.REVIEW);
        assertThat(s.stopReason).contains("修了 2 轮还在");
        assertThat(host.asks.get(host.asks.size() - 1)).contains("#1 读取文件时没有关闭流", "每轮的变化", "第 3 轮审查", "确认交付");
        assertThat(host.developTurns).isEqualTo(3); // two fixes, not a third

        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "确认交付", new Cancellation()));
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.stopReason).isNull();
    }

    @Test
    void theRoundAndSpendLimitsStopTheLoopAndFeedbackStartsItAgain() throws Exception {
        Path repo = repo();
        Host host = new Host("q.txt");
        host.limits = new HarnessHost.ReviewLimits(2, 30);
        host.reviews.add("- [严重] 甲问题 | q.txt:1\nVERDICT: FAIL");
        host.reviews.add("- [一般] 完全不同的乙问题 | other.txt:9\nVERDICT: FAIL");
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0106-aaaa", repo, "加 q.txt");
        HarnessState s = approved(harness, task, host);

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.stopReason).contains("上限（2 轮）");

        // The user's words go to the developer; the rules count afresh, and the next round passes.
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "乙问题按这个思路改：先判空", new Cancellation()));
        assertThat(host.calls(CodingHarness.DEVELOP).get(host.calls(CodingHarness.DEVELOP).size() - 1).prompt())
                .contains("先判空", "完全不同的乙问题");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);

        // Spend: the first failing round already stops once the task has spent the limit.
        Path repo2 = Files.createDirectories(tmp.resolve("other"));
        Host dear = new Host("d.txt");
        dear.roots = List.of(tmp);
        dear.spent = new BigDecimal("31");
        dear.reviews.add("- [严重] 甲问题 | d.txt:1\nVERDICT: FAIL");
        HarnessState d = approved(harness, task("t-0107-aaaa", repo(repo2), "加 d.txt"), dear);
        assertThat(dear.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(d.stopReason).contains("费用上限");
    }

    @Test
    void aTaskFromBeforeAcceptanceStillReviewsAndDelivers() throws Exception {
        Path repo = repo();
        Host host = new Host("l.txt");
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0108-aaaa", repo, "加 l.txt");
        HarnessState started = harness.start(task, null);
        started.steps.removeIf(st -> st.key.equals(CodingHarness.ACCEPT));
        String json = started.toJson().replace(",\"reviews\":[]", "").replace(",\"waived\":[]", "");
        HarnessState s = HarnessState.fromJson(json);
        harness.drive(new HarnessRun(host, task, s, null, new Cancellation()));
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "确认规格", new Cancellation()));

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.calls(CodingHarness.ACCEPT)).isEmpty();
        assertThat(s.reviews).extracting(HarnessState.ReviewRound::kind).containsExactly("review");
    }


    // --- conflicts at delivery ---

    /** Both sides kept: main's line and the task's. */
    private static String keepBoth(Path dir) {
        write(dir.resolve("shared.txt"), "main\nhi\n");
        return "shared.txt：保留了 main 的一行和本任务的 hi";
    }

    private static void write(Path p, String text) {
        try {
            Files.writeString(p, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A repository with shared.txt, a task that changes it, its spec approved after main changed the same line. */
    private HarnessState conflicted(CodingHarness harness, Task task, Host host, Path repo) throws Exception {
        HarnessState s = harness.start(task, null);
        harness.drive(new HarnessRun(host, task, s, null, new Cancellation()));
        Files.writeString(repo.resolve("shared.txt"), "main\n");
        git(repo, "commit", "-q", "-am", "main 改了 shared");
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "确认规格", new Cancellation()));
        return s;
    }

    private Path sharedRepo() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("shared.txt"), "base\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "add shared");
        return repo;
    }

    private static boolean rebasing(Path dir) throws Exception {
        return Files.isDirectory(Path.of(git(dir, "rev-parse", "--absolute-git-dir")).resolve("rebase-merge"));
    }

    @Test
    void aConflictOnTheSameLineIsResolvedKeepingBothSidesAndMergedAsOneCommit() throws Exception {
        Path repo = sharedRepo();
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0201-aaaa", repo, "改 shared.txt");
        Host host = new Host("shared.txt");
        host.reviewerModel = "gpt-5";
        HarnessState s = conflicted(harness, task, host, repo);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(Files.readString(repo.resolve("shared.txt"))).isEqualTo("main\nhi\n");
        assertThat(git(repo, "log", "--format=%s", "-3")).isEqualTo("改 shared.txt\nmain 改了 shared\nadd shared");
        assertThat(s.step(CodingHarness.RESOLVE).status).isEqualTo(HarnessState.Status.DONE);
        assertThat(s.steps).extracting(st -> st.key).endsWith(CodingHarness.RESOLVE, CodingHarness.DELIVER);
        // The task's two commits (the work, then its fix) were squashed first: one conflict, resolved in one go.
        assertThat(s.conflicts).singleElement().satisfies(r -> {
            assertThat(r.passed()).isTrue();
            assertThat(r.files()).containsExactly("shared.txt");
            assertThat(r.checks()).extracting(HarnessState.PlatformCheck::name).containsExactly("冲突标记", "继续变基", "验证命令", "审查");
            assertThat(r.checks()).allMatch(HarnessState.PlatformCheck::passed);
        });
        // The developer, in its own conversation, saw the conflicted part and what main did; another model reviewed.
        Call resolve = host.calls(CodingHarness.RESOLVE).get(0);
        assertThat(resolve.conversation()).isEqualTo(s.conversation);
        assertThat(resolve.prompt()).contains("shared.txt", "<<<<<<<", "main 改了 shared", "规格");
        assertThat(host.calls(CodingHarness.RESOLVE_REVIEW)).singleElement()
                .satisfies(c -> assertThat(c.model()).isEqualTo("gpt-5"));
        assertThat(host.output).isNotNull();
        assertThat(s.deliveryNote).contains("自动解决了冲突");
        assertThat(git(repo, "status", "--porcelain")).isEmpty();
    }

    @Test
    void aResolutionThatLeavesMarkersGoesBackAndTwoFailedRoundsStopWithTheBranchAsItWas() throws Exception {
        Path repo = sharedRepo();
        CodingHarness harness = new CodingHarness(60);
        Host host = new Host("shared.txt");
        host.resolves.add(dir -> {
            write(dir.resolve("shared.txt"), "<<<<<<< HEAD\nmain\n=======\nhi\n>>>>>>> task\n");
            return "解决了";
        });
        HarnessState s = conflicted(harness, task("t-0202-aaaa", repo, "改 shared.txt"), host, repo);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.conflicts).extracting(HarnessState.ConflictRound::passed).containsExactly(false, true);
        assertThat(s.conflicts.get(0).reason()).contains("冲突标记");
        assertThat(host.calls(CodingHarness.RESOLVE).get(1).prompt()).contains("还有冲突标记");
        assertThat(Files.readString(repo.resolve("shared.txt"))).isEqualTo("main\nhi\n");

        // Checks that keep failing: two goes, then the user decides; main is as it was, no rebase left half-done.
        Path repo2 = sharedRepo(Files.createDirectories(tmp.resolve("p2")));
        Host stuck = new Host("shared.txt");
        stuck.roots = List.of(tmp);
        for (int i = 0; i < 2; i++) {
            stuck.resolves.add(dir -> {
                write(dir.resolve("shared.txt"), "main\n"); // the task's side dropped: the checks fail
                return "用 main 的";
            });
        }
        Task t2 = task("t-0203-aaaa", repo2, "改 shared.txt");
        HarnessState s2 = conflicted(harness, t2, stuck, repo2);
        String mainTip = git(repo2, "rev-parse", "main");
        assertThat(stuck.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s2.waitingOn).isEqualTo(CodingHarness.RESOLVE);
        // The resolution failed the checks, and so did the fix after it.
        assertThat(s2.conflicts).extracting(HarnessState.ConflictRound::passed).containsExactly(false, false);
        assertThat(s2.conflicts.get(0).reason()).contains("验证命令没有通过");
        assertThat(stuck.calls(CodingHarness.RESOLVE).get(1).prompt()).contains("变基已经完成", "验证命令没有通过");
        assertThat(stuck.asks.get(stuck.asks.size() - 1)).contains("试了 2 轮", "shared.txt", "回复「继续」");
        assertThat(rebasing(Path.of(s2.dir))).isFalse();
        // The rebase went through and was fixed on before the rounds ran out: all of it is undone.
        assertThat(git(Path.of(s2.dir), "rev-parse", "HEAD")).isEqualTo(git(repo2, "rev-parse", s2.branch));
        assertThat(git(Path.of(s2.dir), "log", "--format=%s")).doesNotContain("main 改了 shared", "解决冲突后修正");
        assertThat(s2.baseSha).isNotEqualTo(mainTip);
        assertThat(git(Path.of(s2.dir), "status", "--porcelain")).isEmpty();
        assertThat(Files.readString(repo2.resolve("shared.txt"))).isEqualTo("main\n");
        assertThat(git(repo2, "log", "--format=%s", "-1")).isEqualTo("main 改了 shared");
        assertThat(git(repo2, "rev-parse", "main")).isEqualTo(mainTip);

        // A short reply with an instruction in it is not a bare "go on": the instruction reaches the resolver.
        assertThat(CodingPrompts.bareApproval("好的，继续")).isTrue();
        assertThat(CodingPrompts.bareApproval("继续，以main为准")).isFalse();
        int before = stuck.calls(CodingHarness.RESOLVE).size();
        harness.drive(new HarnessRun(stuck, t2.withWorkspaceDir(s2.dir), s2, "继续，以main为准", new Cancellation()));
        assertThat(stuck.calls(CodingHarness.RESOLVE).get(before).prompt()).contains("继续，以main为准");
        assertThat(stuck.status).isEqualTo(TaskStatus.DONE);
        assertThat(Files.readString(repo2.resolve("shared.txt"))).isEqualTo("main\nhi\n");
    }

    @Test
    void contradictoryIntentsStopForTheUserAndTheirWordAtDeliveryGoesToTheResolver() throws Exception {
        Path repo = sharedRepo();
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0204-aaaa", repo, "改 shared.txt");
        Host host = new Host("shared.txt");
        host.resolves.add(dir -> "看了两边。\nCONFLICT: INTENT\nmain 把这一行改成了 main，本任务改成了 hi，含义互斥。推荐保留双方各一行。");
        HarnessState s = conflicted(harness, task, host, repo);

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(CodingHarness.RESOLVE);
        assertThat(host.asks.get(host.asks.size() - 1)).contains("意图互相矛盾", "推荐保留双方各一行").doesNotContain("CONFLICT: INTENT");
        assertThat(rebasing(Path.of(s.dir))).isFalse();

        // Stopped at delivery as a task from before this step would be: the user's words reach the resolver.
        s.steps.removeIf(st -> st.key.equals(CodingHarness.RESOLVE));
        s.step(CodingHarness.DELIVER).status = HarnessState.Status.WAITING;
        s.waitingOn = CodingHarness.DELIVER;
        int before = host.calls(CodingHarness.RESOLVE).size();
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "你自己解决冲突，以 main 为准", new Cancellation()));

        List<Call> after = host.calls(CodingHarness.RESOLVE).subList(before, host.calls(CodingHarness.RESOLVE).size());
        assertThat(after).isNotEmpty().allMatch(c -> c.prompt().contains("你自己解决冲突，以 main 为准"));
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(Files.readString(repo.resolve("shared.txt"))).isEqualTo("main\nhi\n");
    }

    @Test
    void theUsersOwnChangesInTheWayStopTheMergeUntouchedAndTheSameStopIsToldOnce() throws Exception {
        Path repo = sharedRepo();
        CodingHarness harness = new CodingHarness(60);
        Task task = task("t-0205-aaaa", repo, "改 shared.txt");
        Host host = new Host("shared.txt");
        HarnessState s = harness.start(task, null);
        harness.drive(new HarnessRun(host, task, s, null, new Cancellation()));
        Files.writeString(repo.resolve("shared.txt"), "my local edit\n"); // uncommitted, the same file the task changes
        String status = git(repo, "status", "--porcelain");
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "确认规格", new Cancellation()));

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(CodingHarness.DELIVER);
        int asks = host.asks.size();
        assertThat(host.asks.get(asks - 1)).contains("未提交的改动");
        assertThat(Files.readString(repo.resolve("shared.txt"))).isEqualTo("my local edit\n");
        assertThat(git(repo, "status", "--porcelain")).isEqualTo(status);
        assertThat(git(repo, "stash", "list")).isEmpty();

        // "Continue" with nothing changed: the same stop, not told again.
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "继续", new Cancellation()));
        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(host.asks).hasSize(asks);
        assertThat(host.repeats.get(host.repeats.size() - 1)).isTrue();
        assertThat(Files.readString(repo.resolve("shared.txt"))).isEqualTo("my local edit\n");

        // Another reason is news: the checkout moved to another branch.
        git(repo, "checkout", "-q", "--", "shared.txt");
        git(repo, "checkout", "-q", "-b", "other");
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "继续", new Cancellation()));
        assertThat(host.asks).hasSize(asks + 1);
        assertThat(host.asks.get(asks)).contains("分支 other");
        assertThat(host.repeats.get(host.repeats.size() - 1)).isFalse();

        git(repo, "checkout", "-q", "main");
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "继续", new Cancellation()));
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(Files.readString(repo.resolve("shared.txt"))).isEqualTo("hi\n");
    }

    @Test
    void aReplyToAStopAtIntakeOrDevelopIsUsed() throws Exception {
        Path repo = repo();
        CodingHarness harness = new CodingHarness(60);
        Host host = new Host("n.txt");
        host.idleDevelops = 1;
        Task task = task("t-0206-aaaa", (String) null, "加 n.txt");
        HarnessState s = harness.start(task, null);
        harness.drive(new HarnessRun(host, task, s, null, new Cancellation()));
        assertThat(s.waitingOn).isEqualTo(CodingHarness.INTAKE);

        harness.drive(new HarnessRun(host, task, s, "代码在 " + repo + "，用这个", new Cancellation()));
        assertThat(s.waitingOn).isEqualTo(CodingHarness.SPEC);
        assertThat(s.repoRoot).isEqualTo(repo.toRealPath().toString());

        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "确认规格", new Cancellation()));
        assertThat(s.waitingOn).isEqualTo(CodingHarness.DEVELOP);
        harness.drive(new HarnessRun(host, task.withWorkspaceDir(s.dir), s, "请新建 n.txt，内容写 hi", new Cancellation()));
        List<Call> develops = host.calls(CodingHarness.DEVELOP);
        assertThat(develops.get(develops.size() - 1).prompt()).contains("请新建 n.txt，内容写 hi");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
    }

    private Path sharedRepo(Path dir) throws Exception {
        Path repo = repo(dir);
        Files.writeString(repo.resolve("shared.txt"), "base\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "add shared");
        return repo;
    }

    private Path repo() throws Exception {
        return repo(Files.createDirectories(tmp.resolve("project")));
    }

    private Path repo(Path repo) throws Exception {
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "demo\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        git(repo, "config", "user.name", "tester");
        git(repo, "config", "user.email", "tester@example.com");
        return repo;
    }

    private static Task task(String id, Path repo, String title) {
        return task(id, repo.toString(), title);
    }

    private static Task task(String id, String repo, String title) {
        Instant now = Instant.now();
        return new Task(id, title, "按说明做", TaskSource.WEB_CHAT, repo, null, "coding", TaskStatus.RUNNING, 0,
                BigDecimal.ZERO, null, AgentType.CLAUDE, null, "tester", null, now, now, null, now, null);
    }

    private static String git(Path dir, String... args) throws Exception {
        Git.Result r = new Git(Map.of("PATH", System.getenv("PATH"))).git(dir, args);
        assertThat(r.ok()).as("git %s: %s", String.join(" ", args), r.output()).isTrue();
        return r.out().strip();
    }
}
