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
    public static final String DESCRIPTION = "任务类型，必须三选一：coding = 写或改代码（按 PRD → 开发 → 验收三步执行，耗时和花费都更高）；"
            + "general = 其他所有工作（写文档、做 PPT、调研、整理资料、生成 skill 等），直接执行；"
            + "complex = 大而复杂、能拆成多块同时做的研究或执行（多方面调研、批量处理、多份材料分头分析等）："
            + "它先拆成子任务并行执行，全部完成后自动汇总出结论。拿不准就选 general";

    /** The {@code kind} the agent passed, as a stored kind name; anything but coding or general is refused. */
    public static String require(JsonNode args) {
        String v = args.path("kind").asText("").strip().toLowerCase(Locale.ROOT);
        if (v.equals("coding") || v.equals("general") || v.equals("complex")) {
            return v;
        }
        throw new McpToolException("kind 必须是 coding、general 或 complex：写或改代码选 coding，能拆成多块同时做的大任务选 complex，其他工作选 general");
    }

    public static String label(String stored) {
        return switch (TaskKind.fromStorage(stored)) {
            case CODING -> "编码";
            case COMPLEX -> "复杂";
            default -> "普通";
        };
    }

    private TaskKinds() {
    }
}
