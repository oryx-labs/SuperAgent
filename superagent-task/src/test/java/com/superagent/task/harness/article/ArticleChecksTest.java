package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Brief;
import com.superagent.task.harness.HarnessState.Article.Issue;
import com.superagent.task.harness.HarnessState.Article.Section;
import com.superagent.task.harness.article.ArticleChecks.Doc;
import com.superagent.task.harness.article.ArticleChecks.LinkStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Each platform check on its own: what fails, what only warns, and what passes. */
class ArticleChecksTest {

    private static final Brief WECHAT = new Brief("公众号", "产品经理", null, "x", null, 100, null, null, null, null);

    private static String levels(List<Issue> issues) {
        return String.join(",", issues.stream().map(Issue::level).toList());
    }

    private static String han(int n, int seed) {
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < n; i++) {
            sb.append((char) (0x4E00 + r.nextInt(0x5000)));
        }
        return sb.toString();
    }

    @Test
    void wordCountFailsBeyondThirtyPercentAndWarnsBeyondFifteen() {
        assertThat(levels(ArticleChecks.words(Doc.of(han(100, 1)), WECHAT))).isEqualTo("pass");
        assertThat(levels(ArticleChecks.words(Doc.of(han(80, 1)), WECHAT))).isEqualTo("warn");
        assertThat(levels(ArticleChecks.words(Doc.of(han(60, 1)), WECHAT))).isEqualTo("fail");
        assertThat(ArticleChecks.count("提纲 outline 3 个")).isEqualTo(5); // Han characters count one each, Latin words one each
    }

    @Test
    void structureHeadingsSectionsPlaceholdersAndCitations() {
        assertThat(levels(ArticleChecks.headings(Doc.of("# 标题\n## 一\n### 二\n")))).isEqualTo("pass");
        assertThat(levels(ArticleChecks.headings(Doc.of("# 标题\n## 一\n#### 跳级\n")))).isEqualTo("fail");
        assertThat(levels(ArticleChecks.headings(Doc.of("# 一\n# 二\n")))).isEqualTo("fail");

        List<Section> outline = List.of(new Section("为什么要写提纲", null, List.of(), 100), new Section("小结", null, List.of(), 100));
        assertThat(levels(ArticleChecks.coverage(Doc.of("# T\n## 一、为什么要写提纲\n## 小结\n"), outline))).isEqualTo("pass");
        List<Issue> cov = ArticleChecks.coverage(Doc.of("# T\n## 为什么要写提纲\n## 附录\n"), outline);
        assertThat(cov).anyMatch(i -> i.failed() && i.where().equals("小结"))
                .anyMatch(i -> i.level().equals("warn") && i.where().equals("附录"));

        assertThat(levels(ArticleChecks.placeholders(Doc.of("正文写完了。"), p -> true))).isEqualTo("pass");
        assertThat(ArticleChecks.placeholders(Doc.of("这里（待补）。还有 [图3] 和 TODO"), p -> true)).hasSize(3).allMatch(Issue::failed);
        assertThat(ArticleChecks.placeholders(Doc.of("![图](img/a.png)"), p -> false)).singleElement().satisfies(i -> assertThat(i.failed()).isTrue());

        assertThat(ArticleChecks.citations(Doc.of("如调研所说 [S1]，还有（S3）"), java.util.Set.of("S1", "S2")))
                .anyMatch(i -> i.failed() && i.where().equals("S3"))
                .anyMatch(i -> i.level().equals("warn") && i.where().equals("S2"));
        assertThat(levels(ArticleChecks.citations(Doc.of("见 [S1, S2]"), java.util.Set.of("S1", "S2")))).isEqualTo("pass");

        String title = "# " + "很长的标题".repeat(7) + "\n正文";
        assertThat(levels(ArticleChecks.wechat(Doc.of(title), "公众号"))).isEqualTo("warn");
        assertThat(levels(ArticleChecks.wechat(Doc.of("# " + "长".repeat(65)), "公众号"))).isEqualTo("fail");
        assertThat(levels(ArticleChecks.wechat(Doc.of("# 短标题"), "公众号"))).isEqualTo("pass");
    }

    @Test
    void linksFailOnlyWhenGoneAndWarnWhenBlockedOrRefused() {
        Map<String, LinkStatus> answers = Map.of(
                "https://ok.example/a", new LinkStatus(200, null),
                "https://gone.example/a", new LinkStatus(404, null),
                "https://nodns.example/a", new LinkStatus(0, "dns"),
                "https://wall.example/a", new LinkStatus(403, null),
                "https://busy.example/a", new LinkStatus(429, null),
                "https://slow.example/a", new LinkStatus(0, "timeout"),
                "http://10.0.0.1/a", new LinkStatus(0, "blocked: 内网地址"));
        String draft = String.join("\n", answers.keySet().stream().map(u -> "见 " + u + " 。").toList());
        List<Issue> out = ArticleChecks.links(Doc.of(draft), answers::get);
        assertThat(out).filteredOn(Issue::failed).extracting(Issue::where)
                .containsExactlyInAnyOrder("https://gone.example/a", "https://nodns.example/a");
        assertThat(out).filteredOn(i -> i.level().equals("warn")).extracting(Issue::where)
                .containsExactlyInAnyOrder("https://wall.example/a", "https://busy.example/a", "https://slow.example/a", "http://10.0.0.1/a");
        assertThat(out).anyMatch(i -> i.note().contains("内网地址"));
        assertThat(ArticleChecks.linkLevel(new LinkStatus(406, null))).isEqualTo("warn");
        assertThat(levels(ArticleChecks.links(Doc.of("见 https://ok.example/a 。"), answers::get))).isEqualTo("pass");
    }

    @Test
    void numbersNotInTheSourcesWarnAndAreHandedToTheFactChecker() {
        String pool = "受访的 12 个团队里有 9 个先写提纲，返工减少 35%。";
        List<String> untraced = new ArrayList<>();
        assertThat(levels(ArticleChecks.numbers(Doc.of("2024 年调研的 12 个团队，返工少了 35%。"), pool, untraced))).isEqualTo("pass");
        assertThat(untraced).isEmpty(); // the year doesn't count
        assertThat(levels(ArticleChecks.numbers(Doc.of("12 个团队，返工少了 48%，节省 300 小时。"), pool, untraced))).isEqualTo("warn");
        assertThat(untraced).containsExactly("48%", "300");
        assertThat(ArticleChecks.numbers(Doc.of("12 个团队"), "", new ArrayList<>())).isEmpty(); // no sources, no tracing
    }

    @Test
    void duplicateParagraphsFailFromEightyPercentAndWarnFromSixty() {
        String p = han(60, 3);
        assertThat(levels(ArticleChecks.duplicates(Doc.of(p + "\n\n" + p + "\n")))).isEqualTo("fail");
        String q = p.substring(0, 50) + han(10, 4);
        assertThat(levels(ArticleChecks.duplicates(Doc.of(p + "\n\n" + q + "\n")))).isEqualTo("warn");
        assertThat(levels(ArticleChecks.duplicates(Doc.of(p + "\n\n" + han(60, 5) + "\n")))).isEqualTo("pass");
    }

    @Test
    void copyingFailsOnALongVerbatimRunOutsideQuotesAndWarnsOnHeavyOverlap() {
        String source = han(400, 6);
        assertThat(ArticleChecks.copying(Doc.of(source.substring(0, 150) + han(50, 7)), source))
                .anyMatch(i -> i.failed() && i.note().contains("连续"));
        assertThat(ArticleChecks.copying(Doc.of("“" + source.substring(0, 150) + "”" + han(400, 7)), source)).noneMatch(Issue::failed);
        // Many short borrowed pieces: no long run, but a third of the text is the source's.
        StringBuilder mixed = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            mixed.append(source, i * 30, i * 30 + 15).append(han(20, 10 + i));
        }
        assertThat(levels(ArticleChecks.copying(Doc.of(mixed.toString()), source))).isEqualTo("warn");
        assertThat(levels(ArticleChecks.copying(Doc.of(han(300, 8)), source))).isEqualTo("pass");
    }

    @Test
    void longParagraphsFailLongSentencesWarnAndWechatIsStricter() {
        String sentences = (han(20, 9) + "。").repeat(1);
        assertThat(levels(ArticleChecks.lengths(Doc.of(sentences), "报告"))).isEqualTo("pass");
        assertThat(ArticleChecks.lengths(Doc.of((han(40, 9) + "。").repeat(13)), "报告")).anyMatch(Issue::failed);
        assertThat(levels(ArticleChecks.lengths(Doc.of((han(40, 9) + "。").repeat(5)), "公众号"))).isEqualTo("warn");
        assertThat(levels(ArticleChecks.lengths(Doc.of((han(40, 9) + "。").repeat(5)), "报告"))).isEqualTo("pass");
        assertThat(ArticleChecks.lengths(Doc.of(han(70, 9) + "。" + han(20, 1) + "。"), "报告"))
                .anyMatch(i -> i.check().equals("句子长度") && i.level().equals("warn"));
    }

    @Test
    void fillerIsAHintOnlyAndSensitiveWordsFailOnlyWhenHighRisk() {
        String heavy = "值得注意的是，综上所述，赋能抓手闭环。".repeat(20);
        assertThat(ArticleChecks.filler(Doc.of(heavy))).isNotEmpty().allMatch(i -> i.level().equals("warn"));
        assertThat(levels(ArticleChecks.filler(Doc.of(han(500, 2))))).isEqualTo("pass");

        assertThat(levels(ArticleChecks.sensitive(Doc.of("教你代开发票")))).isEqualTo("fail");
        assertThat(levels(ArticleChecks.sensitive(Doc.of("这是史上最好用的工具")))).isEqualTo("warn");
        assertThat(levels(ArticleChecks.sensitive(Doc.of("一篇普通的文章")))).isEqualTo("pass");
    }
}
