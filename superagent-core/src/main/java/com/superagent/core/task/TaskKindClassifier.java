package com.superagent.core.task;

import java.util.List;
import java.util.Locale;

/**
 * Keyword fallback that guesses a {@link TaskKind} from a task's title and brief
 * when the caller did not specify one. Deliberately simple and order-sensitive:
 * coding wins over the others because an implementation task often also mentions a
 * report or research. Anything unmatched is {@link TaskKind#GENERAL}.
 */
public final class TaskKindClassifier {

    private record Rule(TaskKind kind, List<String> keywords) {
    }

    // Checked in order; first hit wins.
    private static final List<Rule> RULES = List.of(
            new Rule(TaskKind.CODING, List.of(
                    "代码", "编码", "接口", "api", "bug", "缺陷", "重构", "编译", "构建", "实现",
                    "开发", "函数", "单测", "测试", "部署", "脚本", "代码库", "仓库", "repo",
                    "code", "refactor", "compile", "build", "implement", "endpoint", "fix")),
            new Rule(TaskKind.WRITING, List.of(
                    "写作", "文案", "报告", "撰写", "润色", "文章", "稿", "翻译",
                    "write", "writing", "copywrite", "article", "draft")),
            new Rule(TaskKind.STATS, List.of(
                    "统计", "报表", "数据", "指标", "计算", "汇总",
                    "stat", "statistic", "metric", "aggregate")),
            new Rule(TaskKind.RESEARCH, List.of(
                    "调研", "研究", "对比", "分析", "评估", "综述",
                    "research", "investigate", "compare", "survey")));

    private TaskKindClassifier() {
    }

    public static TaskKind classify(String title, String brief) {
        String text = ((title == null ? "" : title) + "\n" + (brief == null ? "" : brief))
                .toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            for (String kw : rule.keywords()) {
                if (text.contains(kw)) {
                    return rule.kind();
                }
            }
        }
        return TaskKind.GENERAL;
    }
}
