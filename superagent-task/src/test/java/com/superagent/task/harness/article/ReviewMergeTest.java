package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Fact;
import com.superagent.task.harness.HarnessState.Article.Item;
import com.superagent.task.harness.HarnessState.Article.Score;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The revision list merged by rule from the three reviews, where a failed review sends the draft, and their parsing. */
class ReviewMergeTest {

    private static List<Score> scores(int each) {
        List<Score> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            out.add(new Score(i + 1, ReviewMerge.DIMENSIONS.get(i), each, "原句", "建议"));
        }
        return out;
    }

    private static Item editor(String severity, String type, String text, String where) {
        return new Item(null, severity, type, text, where, "编辑", false);
    }

    private static Item reader(String text, String where) {
        return new Item(null, "一般", "表达", text, where, "读者", false);
    }

    @Test
    void theMergeRules() {
        List<Fact> facts = List.of(new Fact("有 10 个团队", "S1", "不成立", "有 9 个", false),
                new Fact("返工少一半", "S1", "无法核实", "", false), new Fact("先写提纲", "S1", "成立", "先写提纲", false));
        List<Item> merged = ReviewMerge.merge(scores(4), List.of(editor("一般", "表达", "开头太平，抓不住人", "为什么要写提纲"),
                        editor("一般", "规范", "术语不统一", "小结"), editor("建议", "表达", "可以加个例子", null)),
                facts, List.of(reader("开头读不下去", "为什么要写提纲"), reader("不知道怎么开始写", "怎么写提纲")), null);

        assertThat(merged).extracting(Item::id).startsWith("R1", "R2");
        assertThat(merged).anyMatch(i -> i.severity().equals("严重") && i.text().contains("事实不成立"));
        assertThat(merged).anyMatch(i -> i.severity().equals("一般") && i.text().contains("无法核实"));
        // Raised by both editor and reader: 严重, once.
        assertThat(merged).filteredOn(i -> i.text().contains("开头太平")).singleElement()
                .satisfies(i -> assertThat(i.severity()).isEqualTo("严重"));
        assertThat(merged).noneMatch(i -> i.text().equals("开头读不下去"));
        assertThat(merged).anyMatch(i -> i.from().equals("读者") && i.severity().equals("一般") && i.text().contains("怎么开始写"));
        assertThat(merged).anyMatch(i -> i.text().equals("术语不统一") && i.severity().equals("一般"));
        assertThat(merged).filteredOn(i -> i.severity().equals("建议")).allMatch(i -> !i.blocking());

        List<Score> low = scores(5);
        low.set(2, new Score(3, "证据", 2, "全是断言", "补出处"));
        assertThat(ReviewMerge.merge(low, List.of(), List.of(), List.of(), Boolean.FALSE))
                .anyMatch(i -> i.severity().equals("严重") && i.text().startsWith("证据只有 2 分"))
                .anyMatch(i -> i.severity().equals("严重") && i.text().contains("中心判断"));
    }

    @Test
    void passingNeedsNoOpenItemsSeventyPointsAndNoFalseFact() {
        Item suggestion = editor("建议", "表达", "x", null);
        assertThat(ReviewMerge.total(scores(4), "公众号")).isEqualTo(80);
        assertThat(ReviewMerge.passed(List.of(suggestion), 80, List.of())).isTrue();
        assertThat(ReviewMerge.passed(List.of(suggestion), 60, List.of())).isFalse();
        assertThat(ReviewMerge.passed(List.of(editor("一般", "表达", "x", null)), 90, List.of())).isFalse();
        assertThat(ReviewMerge.passed(List.of(), 90, List.of(new Fact("a", "S1", "不成立", "b", false)))).isFalse();
        // Weights differ by genre: the same scores weigh differently.
        List<Score> s = scores(5);
        s.set(6, new Score(7, "事实准确", 1, "", ""));
        assertThat(ReviewMerge.total(s, "技术文章")).isLessThan(ReviewMerge.total(s, "公众号"));
    }

    @Test
    void routingByKindOfProblemWithEachReturnBounded() {
        Item structural = new Item("R1", "严重", "结构", "需要改提纲", null, "编辑", true);
        Item wording = new Item("R1", "一般", "表达", "啰嗦", null, "编辑", false);
        List<Fact> unverifiable = List.of(new Fact("a", "", "无法核实", "", false), new Fact("b", "S1", "成立", "b", false));

        assertThat(ReviewMerge.route(true, List.of(), List.of(), 0, 0, 0, 2)).isEqualTo("pass");
        assertThat(ReviewMerge.route(false, List.of(structural), List.of(), 0, 0, 0, 2)).isEqualTo("outline");
        assertThat(ReviewMerge.route(false, List.of(structural), List.of(), 1, 0, 0, 2)).isEqualTo("revise");
        assertThat(ReviewMerge.route(false, List.of(wording), unverifiable, 0, 0, 0, 2)).isEqualTo("research");
        assertThat(ReviewMerge.route(false, List.of(wording), unverifiable, 0, 1, 0, 2)).isEqualTo("revise");
        assertThat(ReviewMerge.route(false, List.of(wording), List.of(), 0, 0, 2, 2)).isEqualTo("stop");
    }

    @Test
    void theReviewersAnswersAreReadAndEvidenceIsSpotChecked() {
        ArticlePrompts.Editor e = ArticlePrompts.parseEditor(ArticleHarnessTest.editor(4,
                "- [严重][结构] 第二节应该放前面，需要改提纲 | 怎么写", "需修改"));
        assertThat(e.scores()).hasSize(8).allMatch(x -> x.score() == 4);
        assertThat(e.findings()).singleElement().satisfies(i -> {
            assertThat(i.outline()).isTrue();
            assertThat(i.where()).isEqualTo("怎么写");
        });
        assertThat(e.verdict()).isEqualTo("需修改");

        List<Fact> facts = ArticlePrompts.verify(ArticlePrompts.parseFacts("""
                - 9 个团队先写提纲 | S1 | 成立 | 有 9 个团队先写提纲
                - 返工少一半 | S1 | 成立 | 返工减少了一半
                - 有 10 个团队 | S1 | 不成立 | 有 9 个团队先写提纲
                """), "调研：受访的 12 个团队里，有 9 个团队先写提纲。");
        assertThat(facts).extracting(Fact::verdict).containsExactly("成立", "无法核实", "不成立");
        assertThat(facts.get(1).adjusted()).isTrue();

        ArticlePrompts.Reader r = ArticlePrompts.parseReader("1. 想\n2. 第三段看不懂\n一句话复述：先提纲\n## 问题\n- 第三段看不懂 | 怎么写\n");
        assertThat(r.retelling()).isEqualTo("先提纲");
        assertThat(r.answers()).hasSize(8).startsWith("想", "第三段看不懂");
        assertThat(r.problems()).singleElement().satisfies(i -> assertThat(i.where()).isEqualTo("怎么写"));

        ArticlePrompts.ParsedBrief b = ArticlePrompts.parseBrief("文体：微信公众号\n读者：❓ 产品经理\n目的：会写提纲\n字数：3千字\n");
        assertThat(b.brief().genre()).isEqualTo("公众号");
        assertThat(b.brief().words()).isEqualTo(3000);
        assertThat(b.suggested()).containsEntry("读者", "产品经理");
        assertThat(ArticlePrompts.parseBrief("文体：报告\n读者：a\n目的：b\n字数：100").problems()).anyMatch(p -> p.contains("2000"));
    }
}
