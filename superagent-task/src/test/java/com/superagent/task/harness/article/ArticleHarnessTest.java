package com.superagent.task.harness.article;

import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writing flow with a scripted author and reviewers: the outline waits on the user, the platform's checks and
 * the merged reviews decide where the draft goes, and the rounds are bounded.
 */
class ArticleHarnessTest {

    private static final HarnessHost.Model CHEAP = new HarnessHost.Model("cheap-model", "claude");
    private static final String SOURCE = "调研发现：先写提纲可以减少返工。受访的 12 个团队里，有 9 个团队先写提纲。";
    private static final String BRIEF = """
            文体：公众号
            读者：产品经理
            读者已知：写过需求文档
            目的：读完愿意先写提纲再动笔
            核心观点：先写提纲能少返工
            字数：1500
            语气：平实
            必须用到的资料：sources/S1.md
            不写什么：无
            验收标准：结构清楚
            """;
    private static final String RESEARCH = """
            ## 资料清单
            - S1 | 团队写作调研 | 文件 | 已读 | 先写提纲能减少返工

            ## 主张与证据
            - 先写提纲可以减少返工 | S1 | 已证实

            ## 核心判断
            先写提纲能少返工
            """;
    private static final String OUTLINE = """
            ## 备选标题
            1. 写提纲的方法
            2. 先提纲后动笔

            ## 中心判断
            先写提纲能少返工

            ## 小节
            ### 为什么要写提纲 | 500
            论点：提纲减少返工
            证据：S1

            ### 怎么写提纲 | 500
            论点：一节一个论点
            证据：S1

            ### 小结 | 500
            论点：先提纲后动笔
            证据：S1

            ## 不写什么
            工具推荐
            """;
    private static final String EDITOR_PASS = editor(5, "- [建议][表达] 可以多举一个例子 | 小结", "通过");
    private static final String FACTS_PASS = "- 先写提纲可以减少返工 | S1 | 成立 | 先写提纲可以减少返工";
    private static final String READER = """
            1. 想读下去
            2. 没有
            3. 没有
            4. 没有
            5. 想看更多例子
            6. 调研数据
            7. 先写提纲
            8. 会
            一句话复述：先写提纲再动笔能少返工
            ## 问题
            - 无
            """;

    @TempDir
    Path ws;

    private final ArticleHarness harness = new ArticleHarness(url -> new ArticleChecks.LinkStatus(200, null));

    @BeforeEach
    void sources() throws IOException {
        Files.createDirectories(ws.resolve("sources"));
        Files.writeString(ws.resolve("sources/S1.md"), SOURCE);
    }

    /** A scripted author (by turn label) and reviewers (by review label and round). */
    private static class Host implements HarnessHost {
        IntFunction<String> draft = n -> clean();
        IntFunction<String> revised = n -> clean() + "\n\n修改后的补充段落" + n + "。";
        IntFunction<String> editor = r -> EDITOR_PASS;
        IntFunction<String> facts = r -> FACTS_PASS;
        String text;
        int outlines, drafts, revisions, editors, factChecks;
        final List<String> labels = new ArrayList<>();
        final List<Model> models = new ArrayList<>();
        final List<String> conversations = new ArrayList<>();
        final List<String> revisePrompts = new ArrayList<>();
        final List<String> asks = new ArrayList<>();
        TaskStatus status;
        String output;

        @Override
        public Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation c) {
            String reply = switch (label) {
                case ArticleHarness.BRIEF -> BRIEF;
                case ArticleHarness.RESEARCH -> RESEARCH;
                case ArticleHarness.OUTLINE -> {
                    outlines++;
                    yield OUTLINE;
                }
                case ArticleHarness.DRAFT -> {
                    text = draft.apply(++drafts);
                    yield "已交付初稿";
                }
                case ArticleHarness.REVISE -> {
                    revisePrompts.add(prompt);
                    text = revised.apply(++revisions);
                    StringBuilder sb = new StringBuilder("## 修改说明\n");
                    Matcher m = Pattern.compile("- (R\\d+) \\[").matcher(prompt);
                    while (m.find()) {
                        sb.append("- ").append(m.group(1)).append(" 已改 | 改好了\n");
                    }
                    yield sb.toString();
                }
                default -> throw new IllegalStateException(label);
            };
            return new Turn(reply, BigDecimal.ZERO, false);
        }

        @Override
        public Turn turnOn(Task task, Model model, String prompt, String conversation, String label, Cancellation c) {
            labels.add(label);
            models.add(model);
            conversations.add(conversation);
            String reply = switch (label) {
                case "review-editor" -> editor.apply(++editors);
                case "review-facts" -> facts.apply(++factChecks);
                case "review-reader" -> READER;
                case "review-consistency" -> "一致：意思相同";
                default -> throw new IllegalStateException(label);
            };
            return new Turn(reply, BigDecimal.ZERO, false);
        }

        @Override public Optional<Model> checkerModel(Task task) { return Optional.of(CHEAP); }
        @Override public List<Produced> produced(Task task) {
            return text == null ? List.of() : List.of(new Produced("f1", "写提纲的方法.md", "text/markdown", text.length(), text));
        }
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

    @Test
    void theOutlineWaitsForTheUserAndFeedbackRewritesItUntilConfirmed() {
        Host host = new Host();
        HarnessState s = start();
        drive(host, s, null);

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(ArticleHarness.OUTLINE);
        assertThat(host.asks.get(0)).contains("写提纲的方法", "👉 下一步：回复「确认提纲」");
        assertThat(s.article.brief.genre()).isEqualTo("公众号");
        assertThat(s.article.sections).hasSize(3);
        assertThat(host.drafts).isZero();

        drive(host, s, "第二节加一个反例");
        assertThat(host.outlines).isEqualTo(2);
        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.article.outlineApproved).isFalse();

        drive(host, s, "确认提纲");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.outlineApproved).isTrue();
        assertThat(s.article.approvals).hasSize(1);
        assertThat(s.steps).allMatch(st -> st.status == HarnessState.Status.DONE);
        assertThat(s.article.draftSnapshot).isEqualTo(clean());
        assertThat(host.output).contains("写提纲的方法.md", "编辑评分", "成立 1 条");
        // The editor on the task's model, the fact-checker and the reader on the cheaper one, each in a new session.
        assertThat(host.labels).containsExactly("review-editor", "review-facts", "review-reader", "review-consistency");
        assertThat(host.models).containsExactly(null, CHEAP, CHEAP, CHEAP);
        assertThat(host.conversations).doesNotHaveDuplicates().doesNotContain(s.conversation);
        // What the task service stores between runs reads back the same.
        HarnessState back = HarnessState.fromJson(s.toJson());
        assertThat(back.article.brief).isEqualTo(s.article.brief);
        assertThat(back.article.reviews).isEqualTo(s.article.reviews);
        assertThat(back.article.checks).isEqualTo(s.article.checks);
    }

    @Test
    void aFailedCheckGoesToReviseAndIsCheckedAgainBeforeTheReview() {
        Host host = new Host();
        host.draft = n -> clean() + "\n\nTODO 这里还要补一段。";
        HarnessState s = confirmed(host);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.checks).extracting(HarnessState.Article.CheckRound::passed).containsExactly(false, true);
        assertThat(s.article.checks.get(0).items()).anyMatch(i -> i.check().equals("占位符") && i.failed());
        assertThat(host.revisions).isEqualTo(1);
        assertThat(host.revisePrompts.get(0)).contains("平台核对没通过", "占位符");
        assertThat(s.article.revisions.get(0).from()).isEqualTo("check");
        assertThat(host.editors).isEqualTo(1); // reviewed only once the checks passed
    }

    @Test
    void aFailedReviewGoesToReviseThenCheckAndReviewAgain() {
        Host host = new Host();
        host.facts = r -> r == 1 ? "- 有 10 个团队先写提纲 | S1 | 不成立 | 受访的 12 个团队里，有 9 个团队先写提纲" : FACTS_PASS;
        HarnessState s = confirmed(host);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.reviews).extracting(HarnessState.Article.Review::route).containsExactly("revise", "pass");
        assertThat(s.article.reviews.get(0).merged()).anyMatch(i -> i.severity().equals("严重") && i.type().equals("事实"));
        assertThat(s.article.checks).hasSize(2); // revised drafts are checked again before the next review
        assertThat(host.revisePrompts.get(0)).contains("独立评审", "事实不成立");
    }

    @Test
    void afterTwoReturnsTheTaskWaitsOnTheUserWhoCanDeliverAsItStands() {
        Host host = new Host();
        host.editor = r -> editor(4, "- [一般][表达] 开头太平 | 为什么要写提纲", "需修改");
        HarnessState s = confirmed(host);

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(ArticleHarness.REVIEW);
        assertThat(host.revisions).isEqualTo(2);
        assertThat(host.editors).isEqualTo(3);
        assertThat(host.asks.get(host.asks.size() - 1)).contains("开头太平", "👉 下一步：回复「确认交付」");

        drive(host, s, "确认交付");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.acceptedAsIs).isTrue();
        assertThat(host.output).contains("没解决的问题", "开头太平");
    }

    @Test
    void writtenFeedbackAfterTheRoundsRunOutStartsTheBudgetOver() {
        Host host = new Host();
        host.editor = r -> r <= 3 ? editor(4, "- [一般][表达] 开头太平 | 为什么要写提纲", "需修改") : EDITOR_PASS;
        HarnessState s = confirmed(host);
        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);

        drive(host, s, "开头换成一个具体的返工故事");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.revisions).isEqualTo(3);
        assertThat(host.revisePrompts.get(2)).contains("开头换成一个具体的返工故事");
        assertThat(s.article.revisions.get(2).from()).isEqualTo("user");
    }

    @Test
    void aStructuralIssueGoesBackToTheOutlineWhichTheUserConfirmsAgainOnce() {
        Host host = new Host();
        host.editor = r -> r <= 2 ? editor(3, "- [严重][结构] 第二节应该放在第一节前面，需要改提纲 | 怎么写提纲", "需修改") : EDITOR_PASS;
        HarnessState s = confirmed(host);

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(ArticleHarness.OUTLINE);
        assertThat(s.article.outlineApproved).isFalse();
        assertThat(s.article.outlineReturns).isEqualTo(1);
        assertThat(host.outlines).isEqualTo(2);
        assertThat(host.asks.get(host.asks.size() - 1)).contains("请再确认一次");

        drive(host, s, "确认提纲");
        // The second structural issue is handled as a revision, not another return to the outline.
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.approvals).hasSize(2);
        assertThat(host.drafts).isEqualTo(2); // the affected sections rewritten after the new outline
        assertThat(s.article.reviews).extracting(HarnessState.Article.Review::route).containsExactly("outline", "revise", "pass");
        assertThat(host.outlines).isEqualTo(2);
    }

    @Test
    void evidenceTheSourcesDontHoldMakesAClaimUnverifiableAndSendsTheDraftBackForMoreSources() {
        Host host = new Host();
        host.facts = r -> r == 1 ? "- 九成团队先写提纲 | S1 | 成立 | 九成团队都先写提纲" : FACTS_PASS;
        HarnessState s = confirmed(host);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        HarnessState.Article.Fact f = s.article.reviews.get(0).facts().get(0);
        assertThat(f.verdict()).isEqualTo("无法核实");
        assertThat(f.adjusted()).isTrue();
        assertThat(s.article.reviews.get(0).route()).isEqualTo("research");
        assertThat(s.article.researchReturns).isEqualTo(1);
        assertThat(host.outlines).isEqualTo(1); // the confirmed outline stands
        assertThat(host.drafts).isEqualTo(2);
    }

    // --- helpers ---

    private HarnessState confirmed(Host host) {
        HarnessState s = start();
        drive(host, s, null);
        assertThat(s.waitingOn).isEqualTo(ArticleHarness.OUTLINE);
        drive(host, s, "确认提纲");
        return s;
    }

    private HarnessState start() {
        HarnessState s = harness.start(task(), null);
        s.conversation = "author-session";
        return s;
    }

    private void drive(Host host, HarnessState s, String reply) {
        harness.drive(new HarnessRun(host, task(), s, reply, new Cancellation()));
    }

    private Task task() {
        return new Task("t-1", "写提纲的方法", "写一篇公众号文章", TaskSource.WEB_CHAT, null, null, "article",
                TaskStatus.RUNNING, 0, BigDecimal.ZERO, "dear-model", AgentType.CLAUDE, ws.toString(), "ada", null,
                Instant.now(), Instant.now(), null, Instant.now(), null);
    }

    static String editor(int score, String finding, String verdict) {
        StringBuilder sb = new StringBuilder();
        List<String> dims = ReviewMerge.DIMENSIONS;
        for (int i = 0; i < dims.size(); i++) {
            sb.append("维度 ").append(i + 1).append(" ").append(dims.get(i)).append("：").append(score).append(" | 原句 | 建议\n");
        }
        return sb.append("\n## 问题\n").append(finding).append("\n\n## 备选标题\n- 先提纲\n\n结论：").append(verdict).toString();
    }

    /** A draft that passes every platform check: the outline's sections, about 1500 characters, no repeats. */
    static String clean() {
        Random r = new Random(7);
        StringBuilder sb = new StringBuilder("# 写提纲的方法\n");
        for (String section : List.of("为什么要写提纲", "怎么写提纲", "小结")) {
            sb.append("\n## ").append(section).append("\n");
            for (int p = 0; p < 5; p++) {
                sb.append("\n");
                for (int k = 0; k < 4; k++) {
                    for (int c = 0; c < 24; c++) {
                        sb.append((char) (0x4E00 + r.nextInt(0x5000)));
                    }
                    sb.append("。");
                }
                if (p == 0) {
                    sb.append("[S1]");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
