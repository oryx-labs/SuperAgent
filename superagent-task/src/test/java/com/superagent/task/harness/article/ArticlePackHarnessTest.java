package com.superagent.task.harness.article;

import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.skill.Skill;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writing flow run by genre packs: an investigating pack (evidence log, architect / verifier / implementer reviews,
 * the same returns as before) and a pack a company adds itself, which needs no code of its own.
 */
class ArticlePackHarnessTest {

    private static final HarnessHost.Model CHEAP = new HarnessHost.Model("cheap-model", "claude");

    private static final String DESIGN = """
            ---
            genre: tech-design
            research: investigate
            needs_repo: true
            word_range: [100, 5000]
            required_sections:
              - [背景]
              - [方案]
              - [备选方案]
            checks: [code_refs, evidence_ids, numbers, mermaid, concreteness, banned_phrases]
            reviewers: [architect, verifier, implementer]
            weights: 技术文章
            max_open_questions: 2
            ---
            设计文档的写法。
            """;
    private static final String POSTMORTEM = """
            ---
            genre: postmortem
            research: collect
            word_range: [100, 5000]
            required_sections:
              - [时间线]
              - [根因, 原因]
            checks: [concreteness]
            reviewers: [architect, reader]
            ---
            复盘的写法：先时间线，再根因。
            """;
    private static final String GENERAL = "---\ngenre: general\nrequired_sections: [结论]\n---\n通用写法";

    private static final String BRIEF = """
            文体：技术方案-测试
            读者：后端工程师
            读者已知：熟悉 Java
            目的：读完能照着实现缓存
            核心观点：给 Cache 加过期时间
            字数：300
            语气：平实
            必须用到的资料：无
            不写什么：无
            验收标准：能直接开工
            """;
    private static final String EVIDENCE = """
            ## 证据日志
            ### E1 | 代码 | src/Cache.java:3
            看到什么：Cache 类从第 3 行开始。

            ### E2 | 命令 | cat src/Cache.java | head -2
            输出：
            ```
            package demo;
            // header
            ```
            说明：文件头两行。

            ## 未覆盖
            - 没有测过并发

            ## 核心判断
            给 Cache 加过期时间
            """;
    private static final String OUTLINE = """
            ## 备选标题
            1. 缓存过期方案

            ## 中心判断
            给 Cache 加过期时间

            ## 小节
            ### 背景 | 100
            论点：现状没有过期
            证据：E1

            ### 方案 | 100
            论点：加一个过期时间
            证据：E1, E2

            ### 备选方案 | 100
            论点：定时清理
            证据：E1

            ## 不写什么
            无
            """;
    private static final String ARCHITECT_PASS = ArticleHarnessTest.editor(5, "- [建议][表达] 可以多画一张图 | 方案", "通过");
    private static final String VERIFIED = "- Cache 类在第 3 行 | E1 | 成立 | Cache 类从第 3 行开始";
    private static final String IMPLEMENTER_OK = "能否直接开工：能\n\n## 待澄清问题\n- 无\n";

    @TempDir
    Path ws;

    @BeforeEach
    void repo() throws IOException {
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("src/Cache.java"), "package demo;\n// header\nclass Cache {\n  Object get(String k) { return null; }\n}\n");
    }

    private static final class Packs {
        static GenrePacks of(String... nameAndContent) {
            List<Skill> skills = new ArrayList<>();
            Instant now = Instant.now();
            for (int i = 0; i < nameAndContent.length; i += 2) {
                skills.add(new Skill("s" + i, nameAndContent[i], "说明", "写作", nameAndContent[i + 1], "company", true, false, "system", "system", now, now));
            }
            return new GenrePacks(() -> skills);
        }
    }

    private static class Host implements HarnessHost {
        String brief = BRIEF;
        IntFunction<String> research = n -> EVIDENCE;
        IntFunction<String> architect = r -> ARCHITECT_PASS;
        IntFunction<String> verifier = r -> VERIFIED;
        IntFunction<String> implementer = r -> IMPLEMENTER_OK;
        IntFunction<String> draft = n -> n == 1 ? design() : design() + "\n\n修改后补充第" + n + "版说明，见 `Cache.get`。\n";
        String text, error;
        int researches, outlines, revisions, architects, implementers;
        final List<String> labels = new ArrayList<>(), conversations = new ArrayList<>(), prompts = new ArrayList<>(), asks = new ArrayList<>();
        final List<Model> models = new ArrayList<>();
        TaskStatus status;
        String outline = OUTLINE;

        @Override
        public Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation c) {
            prompts.add(label + ":" + prompt);
            String reply = switch (label) {
                case ArticleHarness.BRIEF -> brief;
                case ArticleHarness.RESEARCH -> research.apply(++researches);
                case ArticleHarness.OUTLINE -> {
                    outlines++;
                    yield outline;
                }
                case ArticleHarness.DRAFT -> {
                    text = draft.apply(1);
                    yield "已交付";
                }
                case ArticleHarness.REVISE -> {
                    text = draft.apply(++revisions + 1);
                    yield "## 修改说明\n" + java.util.regex.Pattern.compile("- (R\\d+) \\[").matcher(prompt).results()
                            .map(m -> "- " + m.group(1) + " 已改 | 改好了\n").reduce("", String::concat);
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
            prompts.add(label + ":" + prompt);
            String reply = switch (label) {
                case "review-architect" -> architect.apply(++architects);
                case "review-verifier" -> verifier.apply(1);
                case "review-implementer" -> implementer.apply(++implementers);
                case "review-reader" -> "1. 想读\n2. 没有\n3. 没有\n4. 没有\n5. 没有\n6. 时间线\n7. 能复盘\n8. 会\n一句话复述：给 Cache 加过期时间\n## 问题\n- 无\n";
                case "review-consistency" -> "一致：意思相同";
                default -> throw new IllegalStateException(label);
            };
            return new Turn(reply, BigDecimal.ZERO, false);
        }

        @Override public Optional<Model> checkerModel(Task task) { return Optional.of(CHEAP); }
        @Override public List<Produced> produced(Task task) {
            return text == null ? List.of() : List.of(new Produced("f1", "缓存过期方案.md", "text/markdown", text.length(), text));
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
            this.error = error;
        }
        @Override public Optional<String> connectorToken(String connectorId, String owner) { return Optional.empty(); }
        @Override public Optional<String> connectorSite(String connectorId, String owner) { return Optional.empty(); }
        @Override public List<Path> localRoots() { return List.of(); }
        @Override public Path workRoot() { return Path.of("."); }
    }

    // --- the investigating pack ---

    @Test
    void anInvestigatingPackKeepsAnEvidenceLogAndIsReviewedByArchitectVerifierAndImplementer() {
        Host host = new Host();
        HarnessState s = confirmed(host, "技术方案-测试");

        assertThat(host.status).as(String.valueOf(s.article.checks.stream().flatMap(c -> c.items().stream()).filter(i -> !i.level().equals("pass")).toList()) + host.asks).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.evidence).extracting(HarnessState.Article.Evidence::id).containsExactly("E1", "E2");
        assertThat(s.article.checks.get(s.article.checks.size() - 1).passed()).isTrue();
        assertThat(s.article.checks.get(0).items()).extracting(HarnessState.Article.Issue::check).contains("代码引用", "证据编号", "图", "具体度", "禁用语", "必备小节");
        // Architect on the task's model, verifier and implementer on the cheaper one; each in a session of its own.
        assertThat(host.labels).containsExactly("review-architect", "review-verifier", "review-implementer");
        assertThat(host.models).containsExactly(null, CHEAP, CHEAP);
        assertThat(host.conversations).doesNotHaveDuplicates().doesNotContain(s.conversation);
        // The platform also re-ran the log's command, and it matched.
        assertThat(s.article.reviews.get(0).facts()).anyMatch(f -> f.statement().contains("cat src/Cache.java | head -2") && "成立".equals(f.verdict()));
        assertThat(host.prompts).anyMatch(p -> p.startsWith("review-verifier:") && p.contains("代码 src/Cache.java:3") && p.contains("class Cache"));
        // The author is given the pack's own text.
        assertThat(host.prompts).anyMatch(p -> p.startsWith("draft:") && p.contains("设计文档的写法"));
        HarnessState back = HarnessState.fromJson(s.toJson());
        assertThat(back.article.pack).isEqualTo(s.article.pack);
        assertThat(back.article.evidence).isEqualTo(s.article.evidence);
    }

    @Test
    void withoutAnEvidenceLogTheTaskNeverReachesTheOutline() {
        Host host = new Host();
        host.research = n -> "## 核心判断\n没什么好查的";
        HarnessState s = start(host, "技术方案-测试");

        assertThat(host.status).isEqualTo(TaskStatus.ERROR);
        assertThat(host.error).contains("证据日志");
        assertThat(host.researches).isEqualTo(2);
        assertThat(host.outlines).isZero();
        assertThat(s.step(ArticleHarness.OUTLINE).status).isEqualTo(HarnessState.Status.PENDING);
    }

    @Test
    void aCodeReferenceThatPointsAtNothingIsSentBackToTheAuthorOnce() {
        Host host = new Host();
        host.research = n -> n == 1 ? EVIDENCE.replace("src/Cache.java:3", "src/Nope.java:3") : EVIDENCE;
        start(host, "技术方案-测试");

        assertThat(host.researches).isEqualTo(2);
        assertThat(host.prompts.stream().filter(p -> p.startsWith("research:")).skip(1).findFirst().orElseThrow())
                .contains("证据日志平台核对没通过", "文件不存在");
        assertThat(host.outlines).isEqualTo(1);
    }

    @Test
    void tooManyOpenQuestionsFailTheReviewAndTheDocumentIsRevised() {
        Host host = new Host();
        host.implementer = n -> n == 1 ? "能否直接开工：不能（过期时间的单位没写）\n\n## 待澄清问题\n- 过期时间的单位是什么 | 方案\n- 并发怎么处理 | 方案\n- 失败怎么重试 | 备选方案\n"
                : IMPLEMENTER_OK;
        HarnessState s = confirmed(host, "技术方案-测试");

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.reviews).extracting(HarnessState.Article.Review::route).containsExactly("revise", "pass");
        assertThat(s.article.reviews.get(0).merged()).anyMatch(i -> i.severity().equals("严重") && i.text().contains("超过 2 个"));
        assertThat(host.revisions).isEqualTo(1);
        // One open question is only noted: it never sends the draft back.
        Host few = new Host();
        few.implementer = n -> "能否直接开工：能\n\n## 待澄清问题\n- 过期时间的单位是什么 | 方案\n";
        HarnessState s2 = confirmed(few, "技术方案-测试");
        assertThat(s2.article.reviews).extracting(HarnessState.Article.Review::route).containsExactly("pass");
    }

    @Test
    void aCommandWhoseOutputDoesNotReproduceFailsTheVerificationAndTheRoundsRunOut() {
        Host host = new Host();
        host.research = n -> EVIDENCE.replace("// header", "// 编造的输出");
        HarnessState s = confirmed(host, "技术方案-测试");

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(ArticleHarness.REVIEW);
        assertThat(s.article.reviews).hasSize(3);
        assertThat(s.article.reviews.get(0).facts()).anyMatch(f -> "不成立".equals(f.verdict()) && f.statement().contains("cat src/Cache.java"));
        assertThat(host.asks.get(host.asks.size() - 1)).contains("不一致".isEmpty() ? "" : "事实不成立", "👉 下一步：回复「确认交付」");
    }

    @Test
    void aStructuralIssueFromTheArchitectStillGoesBackToTheOutline() {
        Host host = new Host();
        host.architect = r -> r == 1 ? ArticleHarnessTest.editor(3, "- [严重][结构] 备选方案应该在方案之前，需要改提纲 | 方案", "需修改") : ARCHITECT_PASS;
        HarnessState s = confirmed(host, "技术方案-测试");

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.waitingOn).isEqualTo(ArticleHarness.OUTLINE);
        assertThat(s.article.outlineReturns).isEqualTo(1);
        drive(host, s, "确认提纲");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.article.reviews).extracting(HarnessState.Article.Review::route).containsExactly("outline", "pass");
    }

    @Test
    void aTaskWhoseGenreNeedsARepositoryButHasNoneFailsAtOnce() {
        Host host = new Host();
        HarnessState s = harness(Packs.of("技术方案-测试", DESIGN)).start(task(null), null, "技术方案-测试");
        s.conversation = "author";
        new ArticleHarness(url -> new ArticleChecks.LinkStatus(200, null), Packs.of("技术方案-测试", DESIGN))
                .drive(new HarnessRun(host, task(null), s, null, new Cancellation()));

        assertThat(host.status).isEqualTo(TaskStatus.ERROR);
        assertThat(host.error).contains("本地仓库");
    }

    // --- a pack the company adds ---

    @Test
    void aGenreTheCompanyAddedTakesEffectWithItsOwnSectionsChecksAndReviewers() {
        Host host = new Host();
        host.brief = BRIEF.replace("技术方案-测试", "事故复盘");
        host.draft = n -> n == 1 ? postmortem(false) : postmortem(true);
        host.outline = OUTLINE.replace("### 背景 | 100", "### 时间线 | 100").replace("### 方案 | 100", "### 根因 | 100").replace("### 备选方案 | 100", "### 改进 | 100")
                .replace("E1, E2", "S1").replace("E1", "S1");
        HarnessState s = start(host, "事故复盘", Packs.of("事故复盘", POSTMORTEM, GenrePacks.FALLBACK, GENERAL));
        drive(host, s, "确认提纲", Packs.of("事故复盘", POSTMORTEM, GenrePacks.FALLBACK, GENERAL));

        assertThat(s.article.pack.genre()).isEqualTo("postmortem");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        // Its own required section was missing in the first draft: the check failed and the draft was revised.
        assertThat(s.article.checks.get(0).items()).anyMatch(i -> i.check().equals("必备小节") && i.failed() && i.note().contains("根因"));
        assertThat(s.article.checks).extracting(HarnessState.Article.CheckRound::passed).containsExactly(false, true);
        // Only the checks it asked for ran, and the reviewers are the ones it named.
        assertThat(s.article.checks.get(1).items()).extracting(HarnessState.Article.Issue::check).doesNotContain("代码引用", "图", "禁用语");
        assertThat(host.labels).containsExactly("review-architect", "review-reader", "review-consistency");
        assertThat(s.article.evidence).isEmpty(); // research was `collect`
    }

    @Test
    void aWrongConfigurationFailsLoudlyAndAnUnknownGenreFallsBackToTheGeneralOne() {
        Host host = new Host();
        GenrePacks packs = Packs.of("坏文体", "---\ngenre: bad\ncolour: red\nrequired_sections: [a]\n---\n", GenrePacks.FALLBACK, GENERAL);
        start(host, "坏文体", packs);
        assertThat(host.status).isEqualTo(TaskStatus.ERROR);
        assertThat(host.error).contains("「坏文体」").contains("字段 colour").contains("未知字段");

        Host other = new Host();
        HarnessState s = start(other, "没有这个文体", packs);
        assertThat(s.article.pack.name()).isEqualTo(GenrePacks.FALLBACK);
        assertThat(other.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION); // went on to the outline
    }

    // --- helpers ---

    private ArticleHarness harness(GenrePacks packs) {
        return new ArticleHarness(url -> new ArticleChecks.LinkStatus(200, null), packs);
    }

    private HarnessState confirmed(Host host, String genre) {
        HarnessState s = start(host, genre);
        assertThat(s.waitingOn).isEqualTo(ArticleHarness.OUTLINE);
        drive(host, s, "确认提纲");
        return s;
    }

    private HarnessState start(Host host, String genre) {
        return start(host, genre, Packs.of("技术方案-测试", DESIGN));
    }

    private HarnessState start(Host host, String genre, GenrePacks packs) {
        ArticleHarness h = harness(packs);
        HarnessState s = h.start(task(ws.toString()), null, genre);
        s.conversation = "author-session";
        h.drive(new HarnessRun(host, task(ws.toString()), s, null, new Cancellation()));
        return s;
    }

    private void drive(Host host, HarnessState s, String reply) {
        drive(host, s, reply, Packs.of("技术方案-测试", DESIGN));
    }

    private void drive(Host host, HarnessState s, String reply, GenrePacks packs) {
        harness(packs).drive(new HarnessRun(host, task(ws.toString()), s, reply, new Cancellation()));
    }

    private Task task(String repo) {
        return new Task("t-1", "缓存过期方案", "写一份缓存过期的技术方案", TaskSource.WEB_CHAT, repo, null, "article",
                TaskStatus.RUNNING, 0, BigDecimal.ZERO, "dear-model", AgentType.CLAUDE, ws.toString(), "ada", null,
                Instant.now(), Instant.now(), null, Instant.now(), null);
    }

    /** A design draft that passes every check of the test pack, about 300 characters. */
    static String design() {
        String body = "# 缓存过期方案\n\n## 背景\n\n现状见 `src/Cache.java:3#Cache`，`Cache.get` 没有过期 [E1]。见图 1，`Cache` 读 `Store`。\n\n"
                + "```mermaid\nflowchart TB\n  Cache --> Store\n```\n图 1　Cache 和 Store\n\n## 方案\n\n给 `Cache.get` 加过期时间 [E2]。\n\n## 备选方案\n\n定时清理 `Cache`，放弃的理由见 [E1]。\n";
        return pad(body, 300, 5);
    }

    static String postmortem(boolean withCause) {
        String body = "# 事故复盘\n\n## 时间线\n\n故障从 10:00 开始，`Cache.get` 返回了旧值。\n\n" + (withCause ? "## 根因\n\n`Cache` 没有过期，见 `Cache.java`。\n\n" : "") + "## 改进\n\n给 `Cache` 加过期时间 60 秒。\n";
        return pad(body, 300, 9);
    }

    /** Random Han characters in sentences, added until the draft counts about {@code target} words. */
    private static String pad(String body, int target, long seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder(body);
        while (ArticleChecks.count(sb.toString()) < target) {
            for (int c = 0; c < 20; c++) {
                sb.append((char) (0x4E00 + r.nextInt(0x5000)));
            }
            sb.append("。");
            if (sb.length() % 3 == 0) {
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }
}
