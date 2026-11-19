package com.superagent.task.harness.general;

import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The general flow with a scripted agent and checker: the platform's checks and the independent checker (on the
 * cheaper model, in a new conversation) decide whether the work passed, never the agent's own word.
 */
class GeneralHarnessTest {

    private static final String CHECKLIST = """
            ## 交付物
            - 访谈字幕 | srt

            ## 验收标准
            1. 字幕覆盖整段访谈
            2. [补全] 不编造访谈里没有的内容
            """;
    private static final String SRT = "1\n00:00:01,000 --> 00:00:02,500\n你好\n\n2\n00:00:03,000 --> 00:00:04,000\n再见\n";
    private static final HarnessHost.Model CHEAP = new HarnessHost.Model("cheap-model", "claude");

    /** A scripted agent: the checklist, then work whose files come from {@code files} (by execute round). */
    private static class Host implements HarnessHost {
        String checklist = CHECKLIST;
        Function<Integer, List<Produced>> files = r -> List.of(new Produced("f1", "访谈.srt", "text/plain", SRT.length(), SRT));
        Function<Integer, String> verdict = r -> "1. ✅ 214 条，覆盖全程\n2. ✅ 都能在原文找到\nVERDICT: PASS";
        boolean onItsOwn;
        int executes, checks;
        final List<String> checkPrompts = new ArrayList<>();
        final List<Model> checkModels = new ArrayList<>();
        final List<String> checkConversations = new ArrayList<>();
        final List<String> asks = new ArrayList<>();
        TaskStatus status;
        String output;

        @Override
        public Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation c) {
            return switch (label) {
                case GeneralHarness.CRITERIA -> new Turn(checklist, BigDecimal.ZERO, false);
                default -> {
                    executes++;
                    yield new Turn("交付总结：字幕做好了（第 " + executes + " 次）", BigDecimal.ZERO, false);
                }
            };
        }

        @Override
        public Turn turnOn(Task task, Model model, String prompt, String conversation, String label, Cancellation c) {
            checks++;
            checkPrompts.add(prompt);
            checkModels.add(model);
            checkConversations.add(conversation);
            return new Turn(verdict.apply(checks), BigDecimal.ZERO, false);
        }

        @Override public Optional<Model> checkerModel(Task task) { return Optional.of(CHEAP); }
        @Override public boolean onItsOwn(Task task) { return onItsOwn; }
        @Override public List<Produced> produced(Task task) { return files.apply(executes); }
        @Override public String brief(Task task, boolean first) { return task.brief(); }
        @Override public Task workIn(Task task, String dir) { return task; }
        @Override public void progress(Task task, int percent) { }
        @Override public void note(Task task, String text) { }
        @Override public void ask(Task task, String text) { asks.add(text); }
        @Override public void tell(Task task, String text) { }
        @Override public void save(Task task, HarnessState state) { }
        @Override public void settle(Task task, TaskStatus s, int progress, String output, String error) {
            status = s;
            this.output = output;
        }
        @Override public Optional<String> connectorToken(String connectorId, String owner) { return Optional.empty(); }
        @Override public Optional<String> connectorSite(String connectorId, String owner) { return Optional.empty(); }
        @Override public List<Path> localRoots() { return List.of(); }
        @Override public Path workRoot() { return Path.of("."); }
    }

    private final GeneralHarness harness = new GeneralHarness();

    @Test
    void theFourStepsRunAndTheWorkIsCheckedOnTheCheaperModelInANewConversation() {
        Host host = new Host();
        HarnessState s = drive(host, null);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.steps).allMatch(st -> st.status == HarnessState.Status.DONE);
        assertThat(s.deliverables).containsExactly(new HarnessState.Deliverable("访谈字幕", "srt"));
        assertThat(s.criteria).extracting(HarnessState.Criterion::met).containsExactly(true, true);
        assertThat(s.criteria).extracting(HarnessState.Criterion::agentAdded).containsExactly(false, true);
        assertThat(s.checks).singleElement().satisfies(c -> {
            assertThat(c.passed()).isTrue();
            assertThat(c.verdict()).isEqualTo("PASS");
            assertThat(c.checker()).isEqualTo("cheap-model");
            assertThat(c.platform()).allMatch(HarnessState.PlatformCheck::passed);
        });
        // The checker ran on the cheaper model, in a conversation of its own, seeing the checklist and the file itself.
        assertThat(host.checkModels).containsExactly(CHEAP);
        assertThat(host.checkConversations).doesNotContain(s.conversation);
        assertThat(host.checkPrompts.get(0)).contains("字幕覆盖整段访谈", "00:00:01,000", "交付总结").doesNotContain("这一轮先不要开始做");
        assertThat(host.output).contains("交付总结：字幕做好了", "访谈.srt", "独立检查 PASS（cheap-model）");
    }

    @Test
    void anEmptyFileFailsThePlatformCheckWithoutAskingAModelAndTheSecondRoundPasses() {
        Host host = new Host();
        host.files = r -> r <= 1 ? List.of(new HarnessHost.Produced("f1", "访谈.srt", "text/plain", 0, ""))
                : List.of(new HarnessHost.Produced("f1", "访谈.srt", "text/plain", SRT.length(), SRT));
        HarnessState s = drive(host, null);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.executes).isEqualTo(2);
        assertThat(host.checks).isEqualTo(1); // only once the platform's checks passed
        assertThat(s.checks).extracting(HarnessState.CheckRound::passed).containsExactly(false, true);
        assertThat(s.checks.get(0).verdict()).isNull();
        assertThat(s.checks.get(0).issues()).anyMatch(i -> i.contains("空文件"));
        assertThat(s.round).isEqualTo(2);
    }

    @Test
    void aFailingVerdictGoesBackTwiceThenWaitsOnTheUserWhoCanDeliverAsIs() {
        Host host = new Host();
        host.verdict = r -> r == 3 ? "1. ✅ 有\n2. ❌ 第 3 段是编的" : "1. ❌ 只覆盖了一半\n2. ✅ 可以\nVERDICT: FAIL";
        HarnessState s = drive(host, null);

        assertThat(host.executes).isEqualTo(3); // the first round and two returns
        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(GeneralHarness.CHECK);
        // No VERDICT line counts as a fail; what is still open is listed for the user.
        assertThat(s.checks).extracting(HarnessState.CheckRound::verdict).containsExactly("FAIL", "FAIL", "FAIL");
        assertThat(host.asks).singleElement().asString().contains("第 3 段是编的", "VERDICT", "确认交付");

        harness.drive(new HarnessRun(host, task(), s, "确认交付", new Cancellation()));
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.executes).isEqualTo(3);
        assertThat(host.output).contains("第 3 段是编的");
    }

    @Test
    void onlyAPlainYesDeliversAsIsAndTheUsersWordsCarryTheOpenIssuesIntoTheNextRound() {
        assertThat(List.of("确认交付", "可以", "没问题", "OK", "好的，交付吧")).allMatch(GeneralPrompts::approves);
        assertThat(List.of("先别交付", "不可以", "不确认", "没确认", "暂不交付", "再改一下第 3 段", "no"))
                .noneMatch(GeneralPrompts::approves);

        Host host = new Host();
        host.verdict = r -> r <= 3 ? "1. ❌ 只覆盖了一半\n2. ✅ 可以\nVERDICT: FAIL" : "1. ✅ 全了\n2. ✅\nVERDICT: PASS";
        List<String> prompts = new ArrayList<>();
        Host recording = new Host() {
            @Override
            public Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation c) {
                prompts.add(prompt);
                return super.turn(task, prompt, conversation, first, label, c);
            }
        };
        recording.verdict = host.verdict;
        HarnessState s = drive(recording, null);
        assertThat(recording.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);

        harness.drive(new HarnessRun(recording, task(), s, "先别交付，补上后半段", new Cancellation()));
        assertThat(recording.status).isEqualTo(TaskStatus.DONE);
        assertThat(recording.executes).isEqualTo(4); // another round, not delivered as is
        assertThat(prompts.get(prompts.size() - 1)).contains("先别交付，补上后半段", "上一轮检查（第 3 轮）还没通过", "只覆盖了一半");
    }

    @Test
    void withNoFilesToHandOverTheFinalReplyIsChecked() {
        Host host = new Host();
        host.checklist = "## 交付物\n- 调研结论 | 回复\n\n## 验收标准\n1. 给出三家竞品的定价\n";
        host.files = r -> List.of();
        host.verdict = r -> "1. ✅ 回复里列了三家\nVERDICT: PASS";
        HarnessState s = drive(host, null);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.checks.get(0).platform()).extracting(HarnessState.PlatformCheck::name).containsExactly(OutputChecks.REPLY);
        assertThat(host.checkPrompts.get(0)).contains("没有文件，检查对象是下面的最终回复", "交付总结：字幕做好了");
    }

    @Test
    void aSubtaskOutOfRoundsIsDeliveredWithItsProblemsRatherThanStopping() {
        Host host = new Host();
        host.onItsOwn = true;
        host.verdict = r -> "1. ❌ 缺\n2. ✅\nVERDICT: FAIL";
        drive(host, null);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.executes).isEqualTo(3);
        assertThat(host.output).contains("未通过", "⚠️");
    }

    private HarnessState drive(Host host, String reply) {
        HarnessState s = harness.start(task(), null);
        s.conversation = "task-session";
        harness.drive(new HarnessRun(host, task(), s, reply, new Cancellation()));
        return s;
    }

    private static Task task() {
        return new Task("t-1", "整理访谈字幕", "把访谈录音做成 srt 字幕", TaskSource.WEB_CHAT, null, null, "general",
                TaskStatus.RUNNING, 0, BigDecimal.ZERO, "dear-model", AgentType.CLAUDE, "/tmp/ws", "ada", null,
                Instant.now(), Instant.now(), null, Instant.now(), null);
    }
}
