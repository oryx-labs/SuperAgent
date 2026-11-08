package com.superagent.task;

import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;

import java.util.List;

/**
 * What a complex task and its subtasks are told: the plan step, the subtask brief, and the summing-up once the
 * subtasks have finished. Also the "carry on from" preamble a follow-up task starts with.
 */
final class ComplexTaskPrompts {

    /** Marks the message that hands a complex task its subtasks' results; counting them counts the rounds. */
    static final String SUMMARY_MARK = "【子任务汇总】";
    /** At most this many summing-up rounds; the last one must conclude. */
    static final int MAX_ROUNDS = 3;
    private static final int ALL_RESULTS_CHARS = 60_000;
    private static final int ONE_RESULT_CHARS = 12_000;

    /** A subtask's result, for the summing-up. {@code output} is its final reply, {@code error} why it failed. */
    record Result(Task task, String output, String error) {
    }

    static String plan(String brief, int maxSubtasks, int maxParallel) {
        return """
                你在执行一个复杂任务。你的工作分两步：先拆分、派出子任务；子任务全部结束后，你会收到它们的结果，再汇总出最终结论。

                ## 这一轮：拆分
                1. 理解下面的任务说明，把工作拆成若干个彼此独立、可以同时进行的子任务（最多 %d 个，同时运行 %d 个）。
                2. 每个子任务用 mcp__superagent__create_subtask 创建。子任务看不到这里的说明和讨论，所以它的说明要写全：目标、范围、要用的资料（路径、文件 id、链接原样写上）、要交回什么（结论、数据、引用来源）。
                3. 全部创建后，用几句话列出你的拆分计划，然后结束这一轮。不要自己去做子任务的工作，也不要等待它们。
                4. 如果这件事很小、拆开反而更慢，可以不拆，直接完成它并给出完整结果。

                不要停下来等用户确认：遇到需要拍板的地方，按你推荐的方案做，并在计划里写明。

                ## 任务说明
                %s
                """.formatted(maxSubtasks, maxParallel, brief == null ? "" : brief);
    }

    static String subtask(Task parent, String brief) {
        return """
                你在执行一个子任务，它是复杂任务「%s」拆出来的一部分，和其他子任务同时进行；你的结果会交给主任务汇总。
                - 直接完成它，不要停下来等用户确认：需要拍板时按你推荐的方案做，并在结果里写明你的假设。
                - 最后给出完整的结果（结论、关键数据、来源），不要只写「已完成」——主任务只能看到你最后的回复。
                - 做不到的部分写清楚缺了什么、为什么。

                ## 子任务说明
                %s
                """.formatted(parent.title(), brief == null ? "" : brief);
    }

    static String summary(List<Result> results, int round, int canAdd) {
        long done = results.stream().filter(r -> r.task().status() == TaskStatus.DONE).count();
        long failed = results.stream().filter(r -> r.task().status() == TaskStatus.ERROR).count();
        long stopped = results.size() - done - failed;
        int each = Math.min(ONE_RESULT_CHARS, ALL_RESULTS_CHARS / Math.max(1, results.size()));
        StringBuilder sb = new StringBuilder(SUMMARY_MARK).append("第 ").append(round).append(" 轮：你派出的 ")
                .append(results.size()).append(" 个子任务都结束了（完成 ").append(done).append(" 个");
        if (failed > 0) {
            sb.append("，失败 ").append(failed).append(" 个");
        }
        if (stopped > 0) {
            sb.append("，停止 ").append(stopped).append(" 个");
        }
        sb.append("）。下面是它们的结果。\n");
        int n = 1;
        for (Result r : results) {
            sb.append("\n### ").append(n++).append(". ").append(r.task().title()).append("（")
                    .append(state(r.task().status())).append("，id ").append(r.task().id()).append("）\n");
            if (r.task().status() == TaskStatus.ERROR) {
                sb.append("失败原因：").append(r.error() == null || r.error().isBlank() ? "未知" : clip(r.error(), 500)).append('\n');
            }
            if (r.output() != null && !r.output().isBlank()) {
                sb.append(clip(r.output(), each)).append('\n');
            } else if (r.task().status() != TaskStatus.ERROR) {
                sb.append("（没有结果）\n");
            }
        }
        sb.append("\n## 现在\n- 综合这些结果，写出这个复杂任务的最终结论：完整交付，不是摘要；写明哪些部分缺失或不可靠，以及原因。\n");
        if (round < MAX_ROUNDS && canAdd > 0 && failed + stopped > 0) {
            sb.append("- 如果失败或停止的子任务对结论很关键，可以用 mcp__superagent__create_subtask 补派（改进说明、避开失败原因；还能补派 ")
                    .append(canAdd).append(" 个），补派后结束这一轮，等它完成后会再交给你汇总。这是第 ").append(round).append('/')
                    .append(MAX_ROUNDS).append(" 轮。\n");
        } else {
            sb.append("- 这一轮必须直接写出结论，不能再派子任务。\n");
        }
        sb.append("- 结果被截断时，可以用 mcp__superagent__subtask_output 读某个子任务的全文。\n");
        return sb.toString();
    }

    /** What a follow-up task starts from: the task it carries on and, briefly, the ones before that. */
    static String carryOn(List<Result> chain) {
        if (chain.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 前情：这个任务接着之前的工作做\n不要从头再来：在下面已有结论的基础上继续，用到时直接引用。\n");
        for (int i = chain.size() - 1; i >= 0; i--) {
            Result r = chain.get(i);
            boolean latest = i == 0;
            sb.append("\n### ").append(latest ? "上一次" : "更早").append("：").append(r.task().title()).append("（")
                    .append(state(r.task().status())).append("）\n");
            if (latest && r.task().workspaceDir() != null) {
                sb.append("它的工作目录：").append(r.task().workspaceDir()).append("（需要时可以读里面的文件）\n");
            }
            String out = r.output() == null || r.output().isBlank() ? "（没有结论）" : r.output();
            sb.append(clip(out, latest ? 10_000 : 1_200)).append('\n');
        }
        return sb.append("\n---\n\n").toString();
    }

    private static String state(TaskStatus s) {
        return switch (s) {
            case DONE -> "完成";
            case ERROR -> "失败";
            case STOPPED -> "已停止";
            case DELETED -> "已删除";
            default -> "未结束";
        };
    }

    private static String clip(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "\n…（后面还有 " + (t.length() - max) + " 字，已截断）";
    }

    private ComplexTaskPrompts() {
    }
}
