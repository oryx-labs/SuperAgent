package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Fact;
import com.superagent.task.harness.HarnessState.Article.Item;
import com.superagent.task.harness.HarnessState.Article.Score;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the three reviews become one revision list, by rule (no model), and where a draft that didn't pass goes back
 * to: wording → revise; the outline itself → outline (once); too little evidence → sources (once).
 */
public final class ReviewMerge {

    static final int PASS_SCORE = 70;
    static final int LOW_SCORE = 2;
    static final double UNVERIFIABLE_RETURN = 0.30;
    static final String PASS = "pass", REVISE = "revise", OUTLINE = "outline", RESEARCH = "research", STOP = "stop";

    static final List<String> DIMENSIONS = List.of("结构", "论点", "证据", "可读性", "读者匹配", "原创性", "事实准确", "语言规范");
    /** Each genre's weights for the eight dimensions (the research report §2.5), each set summing to 100. */
    static final Map<String, int[]> WEIGHTS = Map.of(
            "公众号", new int[]{15, 15, 10, 20, 15, 15, 5, 5},
            "报告", new int[]{20, 20, 20, 10, 15, 5, 5, 5},
            "技术文章", new int[]{15, 10, 15, 15, 10, 10, 20, 5},
            "讲义", new int[]{20, 10, 10, 15, 20, 5, 15, 5});

    private ReviewMerge() {
    }

    /** The weighted score out of 100; a dimension the editor left out counts as 0. */
    static int total(List<Score> scores, String genre) {
        int[] w = WEIGHTS.getOrDefault(genre, WEIGHTS.get("公众号"));
        double sum = 0;
        for (Score s : scores) {
            if (s.dimension() >= 1 && s.dimension() <= 8) {
                sum += w[s.dimension() - 1] * Math.max(0, Math.min(5, s.score())) / 5.0;
            }
        }
        return (int) Math.round(sum);
    }

    /**
     * The merged list: 不成立 → 严重, 无法核实 → 一般; what editor and reader both raise → 严重; the editor's own keep their
     * level, the reader's alone are 一般; a dimension at {@value #LOW_SCORE} or below and a retelling that misses the
     * thesis are 严重. 建议 items are kept but never send the draft back.
     */
    static List<Item> merge(List<Score> scores, List<Item> editor, List<Fact> facts, List<Item> reader, Boolean consistent) {
        return merge(scores, editor, facts, reader, consistent, List.of());
    }

    /** {@code extra}: items that keep the severity their reviewer gave them (the implementer's questions). */
    static List<Item> merge(List<Score> scores, List<Item> editor, List<Fact> facts, List<Item> reader, Boolean consistent,
                            List<Item> extra) {
        List<Item> out = new ArrayList<>();
        for (Fact f : facts) {
            if ("不成立".equals(f.verdict())) {
                out.add(new Item(null, "严重", "事实", "事实不成立：" + f.statement() + (f.evidence() == null || f.evidence().isBlank()
                        ? "" : "（资料：" + f.evidence() + "）"), f.statement(), "事实核查", false));
            } else if ("无法核实".equals(f.verdict())) {
                out.add(new Item(null, "一般", "事实", "无法核实：" + f.statement() + "。删掉、写明出处，或降低说法的确定程度",
                        f.statement(), "事实核查", false));
            }
        }
        Set<Item> echoed = new HashSet<>();
        for (Item e : editor) {
            Item both = reader.stream().filter(r -> same(e, r)).findFirst().orElse(null);
            if (both != null) {
                echoed.add(both);
                out.add(new Item(null, "严重", e.type(), e.text() + "（读者也指出：" + both.text() + "）", e.where(), "编辑+读者", e.outline()));
            } else {
                out.add(e);
            }
        }
        reader.stream().filter(r -> !echoed.contains(r))
                .forEach(r -> out.add(new Item(null, "一般", r.type(), r.text(), r.where(), "读者", false)));
        for (Score s : scores) {
            if (s.score() <= LOW_SCORE) {
                out.add(new Item(null, "严重", type(s.name()), s.name() + "只有 " + s.score() + " 分：" + (s.advice() == null ? s.quote() : s.advice()),
                        s.quote(), "编辑评分", false));
            }
        }
        if (Boolean.FALSE.equals(consistent)) {
            out.add(new Item(null, "严重", "论证", "读者读完复述出的意思和提纲的中心判断不一致：中心判断没有讲清楚", null, "读者", false));
        }
        out.addAll(extra);
        List<Item> numbered = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            numbered.add(out.get(i).withId("R" + (i + 1)));
        }
        return numbered;
    }

    static boolean passed(List<Item> merged, int total, List<Fact> facts) {
        return merged.stream().noneMatch(Item::blocking) && total >= PASS_SCORE
                && facts.stream().noneMatch(f -> "不成立".equals(f.verdict()));
    }

    /**
     * Where the draft goes: back to the outline when the editor said the outline must change (once per task), back to
     * the sources when too much can't be verified (once), else a revision while rounds are left, else the user.
     */
    static String route(boolean passed, List<Item> merged, List<Fact> facts, int outlineReturns, int researchReturns,
                        int reviewReturns, int maxReturns) {
        if (passed) {
            return PASS;
        }
        if (outlineReturns < 1 && merged.stream().anyMatch(i -> i.outline() && "严重".equals(i.severity()) && "结构".equals(i.type()))) {
            return OUTLINE;
        }
        long unverifiable = facts.stream().filter(f -> "无法核实".equals(f.verdict())).count();
        if (researchReturns < 1 && !facts.isEmpty() && unverifiable / (double) facts.size() > UNVERIFIABLE_RETURN) {
            return RESEARCH;
        }
        return reviewReturns < maxReturns ? REVISE : STOP;
    }

    private static boolean same(Item a, Item b) {
        String wa = norm(a.where()), wb = norm(b.where());
        if (!wa.isEmpty() && !wb.isEmpty() && (wa.contains(wb) || wb.contains(wa))) {
            return true;
        }
        return ArticleChecks.jaccard(bigrams(a.text()), bigrams(b.text())) >= 0.3;
    }

    private static String norm(String s) {
        return ArticleChecks.squash(s);
    }

    private static Set<String> bigrams(String s) {
        String t = norm(s);
        Set<String> g = new HashSet<>();
        for (int i = 0; i + 2 <= t.length(); i++) {
            g.add(t.substring(i, i + 2));
        }
        return g;
    }

    private static String type(String dimension) {
        return switch (dimension) {
            case "结构" -> "结构";
            case "论点", "证据", "原创性" -> "论证";
            case "事实准确" -> "事实";
            case "语言规范" -> "规范";
            default -> "表达";
        };
    }
}
