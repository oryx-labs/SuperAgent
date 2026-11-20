package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Evidence;
import com.superagent.task.harness.HarnessState.Article.Issue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Each technical check, with a draft that passes and one that fails (or warns). Code references are looked up in this repository. */
class TechChecksTest {

    /** The repository root: the module's directory is one level down. */
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();
    private static final String FILE = "superagent-task/src/main/java/com/superagent/task/harness/article/ArticleChecks.java";

    private static String level(List<Issue> items) {
        return items.stream().anyMatch(Issue::failed) ? "fail" : items.stream().anyMatch(i -> "warn".equals(i.level())) ? "warn" : "pass";
    }

    private static int lineOf(String needle) throws IOException {
        List<String> lines = Files.readAllLines(REPO.resolve(FILE));
        return IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains(needle)).findFirst().orElseThrow() + 1;
    }

    // --- code references ---

    @Test
    void codeReferencesMustPointAtRealPlacesInTheRepository() throws IOException {
        int run = lineOf("public static Result run(Input in) {");
        String good = "入口在 `" + FILE + ":" + run + "#run`，配置在 `pom.xml:1-3`。";
        assertThat(level(TechChecks.codeRefs(good, REPO))).isEqualTo("pass");

        List<Issue> missingFile = TechChecks.codeRefs("见 `superagent-task/src/main/java/Nope.java:3`。", REPO);
        assertThat(level(missingFile)).isEqualTo("fail");
        assertThat(missingFile.get(0).note()).contains("文件不存在");

        List<Issue> range = TechChecks.codeRefs("见 `" + FILE + ":99999`。", REPO);
        assertThat(range.get(0).note()).contains("行号范围无效");
        assertThat(TechChecks.codeRefs("见 `" + FILE + ":20-10`。", REPO).get(0).note()).contains("行号范围无效");

        List<Issue> symbol = TechChecks.codeRefs("见 `" + FILE + ":" + run + "#noSuchSymbol`。", REPO);
        assertThat(symbol.get(0).note()).contains("没有 noSuchSymbol");

        assertThat(TechChecks.codeRefs("见 `../../etc/hosts.xml:1`。", REPO).get(0).note()).contains("仓库外面");
        // Addresses and code blocks are not references; with no repository they can only warn.
        assertThat(level(TechChecks.codeRefs("访问 http://example.com:8080/a.js:3 即可。\n```\nNope.java:3\n```", REPO))).isEqualTo("pass");
        assertThat(level(TechChecks.codeRefs(good, null))).isEqualTo("warn");
    }

    // --- evidence ids ---

    @Test
    void citedEvidenceMustExistAndTheLogMustBeUsed() {
        List<Evidence> log = List.of(new Evidence("E1", "代码", "a.java:1", "x"), new Evidence("E2", "数字", "12 ms", "环境：x"));
        assertThat(level(TechChecks.evidenceIds("结论 [E1]，数字 （E2）。", log))).isEqualTo("pass");
        assertThat(TechChecks.evidenceIds("结论 [E1][E7]。", log)).anyMatch(i -> i.failed() && "E7".equals(i.where()));
        assertThat(TechChecks.evidenceIds("结论没有引用。", log)).anyMatch(i -> i.failed() && i.note().contains("没有引用任何证据编号"));
        assertThat(TechChecks.evidenceIds("结论 [E1]。", log)).anyMatch(i -> "warn".equals(i.level()) && "E2".equals(i.where()));
    }

    // --- numbers ---

    @Test
    void numbersMustBeInTheEvidenceLog() {
        String pool = "E1 命令输出 共 142 行，耗时 85 ms";
        List<String> untraced = new ArrayList<>();
        assertThat(level(TechChecks.numbers(ArticleChecks.Doc.of("扫描了 142 行，用时 85 ms，见 `a/B.java:700`。 [E1]"), pool, untraced))).isEqualTo("pass");
        untraced = new ArrayList<>();
        List<Issue> many = TechChecks.numbers(ArticleChecks.Doc.of("扫描了 142 行，用时 85 ms，吞吐 9000 次，延迟 77 ms。"), pool, untraced);
        assertThat(level(many)).isEqualTo("fail");
        assertThat(untraced).containsExactly("9000", "77");
        untraced = new ArrayList<>();
        assertThat(level(TechChecks.numbers(ArticleChecks.Doc.of("共 142 行，用 85 ms，约 120 个模块，另有 130 个，141 个，143 个，144 个，145 个，146 个，147 个，148 个。"
                .replace("，143 个，144 个，145 个，146 个，147 个，148 个", "")), pool, untraced))).isEqualTo("fail");
    }

    // --- required sections ---

    @Test
    void requiredSectionsComeFromThePack() {
        GenrePack pack = GenrePack.parse("复盘", """
                ---
                genre: postmortem
                required_sections:
                  - [时间线]
                  - [根因, 原因]
                ---
                """);
        assertThat(level(TechChecks.requiredSections(ArticleChecks.Doc.of("# 复盘\n\n## 事故时间线\n\n## 根因分析\n"), pack))).isEqualTo("pass");
        List<Issue> missing = TechChecks.requiredSections(ArticleChecks.Doc.of("# 复盘\n\n## 事故时间线\n"), pack);
        assertThat(missing).singleElement().satisfies(i -> {
            assertThat(i.failed()).isTrue();
            assertThat(i.note()).contains("缺少「根因」");
        });
    }

    // --- concreteness ---

    @Test
    void everySectionNeedsSomethingCheckableAndVagueClaimsNeedANumber() {
        String good = "# 方案\n\n## 现状\n\n入口是 `TaskService.create`，在 `TaskService.java` 里。\n\n## 方案\n\n列表查询从 420 ms 降到 85 ms。\n";
        assertThat(level(TechChecks.concreteness(good))).isEqualTo("pass");

        List<Issue> empty = TechChecks.concreteness("# 方案\n\n## 现状\n\n现状比较复杂，需要重构。\n\n## 方案\n\n用 `Cache` 缓存 60 秒。\n");
        assertThat(empty).singleElement().satisfies(i -> {
            assertThat(i.failed()).isTrue();
            assertThat(i.where()).isEqualTo("现状");
        });

        List<Issue> vague = TechChecks.concreteness("# 方案\n\n## 方案\n\n用 `Cache` 缓存查询结果，从而提升性能、增强可扩展性。\n");
        assertThat(vague).hasSize(1);
        assertThat(vague.get(0).note()).contains("提升性能");
        assertThat(level(TechChecks.concreteness("# 方案\n\n## 方案\n\n用 `Cache` 缓存 60 秒，提升性能：P95 从 420 ms 到 85 ms。\n"))).isEqualTo("pass");
    }

    // --- banned phrases ---

    @Test
    void bannedPhrasesWarnWithTheSentenceAndFailWhenDense() {
        assertThat(level(TechChecks.bannedPhrases(ArticleChecks.Doc.of("缓存把查询从 420 ms 降到 85 ms。"), List.of()))).isEqualTo("pass");

        List<Issue> one = TechChecks.bannedPhrases(ArticleChecks.Doc.of("缓存把查询从 420 ms 降到 85 ms。值得注意的是，过期时间是 60 秒。" + "平实的句子。".repeat(300)), List.of());
        assertThat(one).singleElement().satisfies(i -> {
            assertThat(i.level()).isEqualTo("warn");
            assertThat(i.where()).contains("值得注意的是");
        });

        String dense = "值得注意的是，这很重要。赋能业务。不仅是缓存，更是全方位的提升。综上所述，成功了。";
        assertThat(TechChecks.bannedPhrases(ArticleChecks.Doc.of(dense), List.of())).allMatch(Issue::failed).hasSizeGreaterThanOrEqualTo(3);
        assertThat(level(TechChecks.bannedPhrases(ArticleChecks.Doc.of("这个功能叫流转引擎。" + "平实的句子。".repeat(300)), List.of("流转引擎")))).isEqualTo("warn");
    }

    // --- diagrams ---

    private static final String FLOW = "```mermaid\nflowchart TB\n  subgraph L3[task]\n    TaskService\n    ArticleHarness\n  end\n  TaskService --> ArticleHarness\n```\n";

    private static String doc(String block, String caption, String reference) {
        return "# 文档\n\n## 现状\n\n" + reference + "\n\n" + block + caption + "\n";
    }

    @Test
    void aValidDiagramWithANumberedCaptionAndAReferencePasses() {
        String text = doc(FLOW, "图 1　任务模块里的两个类", "调用关系见图 1：`TaskService` 调 `ArticleHarness`，都在 task 里。");
        assertThat(MermaidCheck.check(text)).singleElement().satisfies(i -> assertThat(i.level()).isEqualTo("pass"));

        String all = "# 文档\n\n正文见图 1、图 2、图 3，涉及用户、ArticleHarness、Skill、Task，状态有 PENDING 和 RUNNING。\n\n"
                + "```mermaid\nsequenceDiagram\n  participant U as 用户\n  participant H as ArticleHarness\n  U->>H: 确认提纲\n  alt 通过\n    H-->>U: 交付\n  end\n```\n图 1　确认提纲\n\n"
                + "```mermaid\nerDiagram\n  Task {\n    string id\n  }\n  Task ||--o{ Skill : uses\n```\n图 2　Task 和 Skill\n\n"
                + "```mermaid\nstateDiagram-v2\n  [*] --> PENDING\n  PENDING --> RUNNING : start\n  RUNNING --> [*]\n```\n图 3　Task 状态\n";
        assertThat(level(MermaidCheck.check(all))).isEqualTo("pass");
    }

    @Test
    void diagramRulesFailWithTheDiagramNamed() {
        String ref = "见图 1：`TaskService`、`ArticleHarness`。";
        assertThat(MermaidCheck.check(doc(FLOW, "", ref))).anyMatch(i -> i.failed() && i.note().contains("没有编号标题"));
        assertThat(MermaidCheck.check(doc(FLOW, "图 2　写错了编号", ref))).anyMatch(i -> i.failed() && i.note().contains("编号应该是 1"));
        assertThat(MermaidCheck.check(doc(FLOW, "图 1　没人引用", "正文没有提到这张图，只讲 `TaskService` 和 `ArticleHarness`。")))
                .anyMatch(i -> i.failed() && i.note().contains("没有引用它"));

        StringBuilder big = new StringBuilder("```mermaid\nflowchart LR\n");
        for (int i = 0; i < 16; i++) {
            big.append("  N").append(i).append(" --> N").append(i + 1).append("\n");
        }
        assertThat(MermaidCheck.check(doc(big + "```\n", "图 1　太大", "见图 1。"))).anyMatch(i -> i.failed() && i.note().contains("最多 15"));
    }

    @Test
    void diagramSyntaxErrorsAreCaught() {
        String ok = "图 1　x";
        String ref = "见图 1：A、B。";
        assertThat(MermaidCheck.check(doc("```mermaid\nflowchart TB\n  A[\"开始\" --> B\n```\n", ok, ref))).anyMatch(i -> i.failed() && i.note().contains("括号或引号不成对"));
        assertThat(MermaidCheck.check(doc("```mermaid\nflowchart TB\n  subgraph S\n  A --> B\n```\n", ok, ref))).anyMatch(i -> i.failed() && i.note().contains("end"));
        assertThat(MermaidCheck.check(doc("```mermaid\nflowchart TB\n  A -->\n```\n", ok, ref))).anyMatch(i -> i.failed() && i.note().contains("缺少一端"));
        assertThat(MermaidCheck.check(doc("```mermaid\nA --> B\n```\n", ok, ref))).anyMatch(i -> i.failed() && i.note().contains("图的类型"));
        assertThat(MermaidCheck.check(doc("```mermaid\nsequenceDiagram\n  alt x\n  A->>B: hi\n```\n", ok, ref))).anyMatch(i -> i.failed() && i.note().contains("缺少对应的 end"));
        assertThat(MermaidCheck.check(doc("```mermaid\n```\n", ok, ref))).anyMatch(i -> i.failed() && i.note().contains("空的"));
    }

    @Test
    void namesThatAppearNowhereInTheTextOnlyWarn() {
        String text = doc(FLOW, "图 1　x", "见图 1，这里只有图，没有提到其中任何一个名字。");
        List<Issue> r = MermaidCheck.check(text);
        assertThat(r).singleElement().satisfies(i -> {
            assertThat(i.level()).isEqualTo("warn");
            assertThat(i.note()).contains("名字要和正文、代码一致");
        });
    }
}
