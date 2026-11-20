package com.superagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.superagent.core.task.TaskKind;
import com.superagent.runtime.mcp.McpToolException;

import java.util.Locale;

/**
 * An agent creating a task must say which kind it is: coding runs three gated steps and costs far more, so it's
 * chosen on purpose, never by default. Shared by the tools that create tasks.
 */
public final class TaskKinds {

    /** For a tool's {@code kind} field. */
    public static final String DESCRIPTION = "任务类型，必须五选一：coding = 在一个代码仓库里写或改代码（要给 repo）。平台在单独的工作区里按"
            + "「需求 → 环境 → 规格（用户确认）→ 开发 → 验证（平台自己跑命令，失败自动修）→ 独立审查 → 交付」执行，做完合并回本地分支或开 PR；"
            + "general = 其他所有工作（写文档、做 PPT、调研、整理资料、生成 skill 等），直接执行；"
            + "complex = 大而复杂、能拆成多块同时做的研究或执行（多方面调研、批量处理、多份材料分头分析等）："
            + "它先拆成子任务并行执行，全部完成后自动汇总出结论；"
            + "subtitle = 把一段音频或视频做成字幕（原话转写，中英混说原样保留，不翻译）：说明里要给音频文件和参考资料（PPT 等）的文件 id，"
            + "平台用全部听写模型同时听写、对齐投票，文字模型只校准有分歧的地方，检查字幕规范后交付 srt 和疑点清单；"
            + "article = 写一篇长文（公众号文章、报告、技术文章、讲义）：说明里写清文体、读者、目的、目标字数和资料（文件 id、链接），"
            + "平台按「需求 → 资料 → 提纲（用户确认）→ 初稿 → 平台核对 → 编辑/事实核查/读者独立评审 → 修改 → 交付」执行，"
            + "交付 Markdown 终稿；文体用 genre 参数指定（技术调研、技术方案、公众号文章、通用长文，或公司自建的文体），不填按通用长文。拿不准就选 general";

    /** For create_task's {@code genre} field (only for kind article). */
    public static final String GENRE_DESCRIPTION = "写作任务（kind=article）的文体：文体包的名字，如 技术调研、技术方案、公众号文章、通用长文，或公司自己加的文体包。"
            + "先用 article_genres 看公司有哪些；按内容选最合适的一个，用户在确认表里可以改；拿不准或不填就按「通用长文」。"
            + "技术调研、技术方案类要读代码仓库，要同时给 repo。";

    /** The genre the agent named for a writing task, or null. */
    public static String genre(JsonNode args) {
        String v = args.path("genre").asText("").strip();
        return v.isEmpty() ? null : v;
    }

    /** The {@code kind} the agent passed, as a stored kind name; anything else is refused. */
    public static String require(JsonNode args) {
        String v = args.path("kind").asText("").strip().toLowerCase(Locale.ROOT);
        if (v.equals("coding") || v.equals("general") || v.equals("complex") || v.equals("subtitle")
                || v.equals("article")) {
            return v;
        }
        throw new McpToolException("kind 必须是 coding、general、complex、subtitle 或 article：写或改代码选 coding，能拆成多块同时做的大任务选 complex，"
                + "把音频做成字幕选 subtitle，写一篇长文（公众号、报告、技术文章、讲义）选 article，其他工作选 general");
    }

    public static String label(String stored) {
        return switch (TaskKind.fromStorage(stored)) {
            case CODING -> "编码";
            case COMPLEX -> "复杂";
            case SUBTITLE -> "字幕";
            case ARTICLE -> "写作";
            default -> "普通";
        };
    }

    private TaskKinds() {
    }
}
