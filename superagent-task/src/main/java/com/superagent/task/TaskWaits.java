package com.superagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskRefDto;

import java.util.ArrayList;
import java.util.List;

/** "Start after" as the task tools speak it: the {@code after} argument, and what a task waits for in their output. */
public final class TaskWaits {

    /** For a tool's {@code after} field. */
    public static final String DESCRIPTION = "要等哪些任务完成后才开始（任务 id，多个用逗号分开，用 list_tasks 查）。"
            + "改同一个仓库或模块、正在跑或在排队的任务要填在这里，避免同时改同一处；接续的任务（continues_from）本来就会等，不用重复填";

    private TaskWaits() {
    }

    /** The ids in {@code after}: a JSON array, or a string separated by commas, spaces or new lines. */
    public static List<String> parse(JsonNode args) {
        JsonNode v = args == null ? null : args.get("after");
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (v.isArray()) {
            v.forEach(n -> add(out, n.asText("")));
        } else {
            for (String s : v.asText("").split("[,，\\s]+")) {
                add(out, s);
            }
        }
        return out;
    }

    private static void add(List<String> out, String id) {
        String s = id.strip();
        if (!s.isEmpty() && !out.contains(s)) {
            out.add(s);
        }
    }

    /** What a task waits for, for a tool's one-line listing ("" when it waits for nothing). */
    public static String line(TaskDto t) {
        List<TaskRefDto> w = t.waitingFor();
        if (w == null || w.isEmpty()) {
            return "";
        }
        return t.blocked() != null ? " | 被阻塞：" + t.blocked() + "，需处理前面的任务或用 run_task_now 直接跑"
                : " | 等待：" + names(w) + " 完成";
    }

    /** As {@link #line}, in full: each task it waits for with its status. */
    public static String detail(TaskDto t) {
        List<TaskRefDto> w = t.waitingFor();
        if (w == null || w.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n依赖（这些任务完成后才开始）：");
        w.forEach(r -> sb.append("\n- ").append(r.title()).append("（").append(r.id()).append("）状态 ").append(r.status()));
        if (t.blocked() != null) {
            sb.append("\n被阻塞：").append(t.blocked()).append("。处理好前面的任务后会自动开始；不想等可以用 run_task_now 直接跑。");
        }
        return sb.toString();
    }

    private static String names(List<TaskRefDto> refs) {
        return String.join("、", refs.stream().map(r -> "「" + r.title() + "」(" + r.id() + ")").toList());
    }
}
