package com.superagent.task.harness.general;

import com.superagent.core.task.Task;
import com.superagent.task.harness.HarnessHost.Produced;
import com.superagent.task.harness.HarnessState;
import com.superagent.task.harness.HarnessState.CheckRound;
import com.superagent.task.harness.HarnessState.Criterion;
import com.superagent.task.harness.HarnessState.Deliverable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the general flow tells the agent and the checker at each step, and how it reads their answers. */
public final class GeneralPrompts {

    /** The line an agent starts with when it has to ask the user (the same marker the single-pass run used). */
    static final String CONFIRM = "NEEDS_CONFIRMATION";
    static final String ADDED = "[补全]";

    private static final int FILE_CHARS = 6_000, ALL_FILES_CHARS = 40_000, REPLY_CHARS = 8_000;
    private static final Pattern DELIVERABLE = Pattern.compile("^\\s*[-*•]\\s*(.+?)\\s*[|｜]\\s*([A-Za-z0-9]+|回复)\\s*$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)\\s*[.、)）]\\s*(.+)$");
    private static final Pattern JUDGED = Pattern.compile("^\\s*(?:标准\\s*)?(\\d+)\\s*[.、)）:：]?\\s*(✅|❌)\\s*[:：|]?\\s*(.*)$");
    private static final Pattern VERDICT = Pattern.compile("VERDICT\\s*[:：]\\s*(PASS|FAIL)", Pattern.CASE_INSENSITIVE);

    private GeneralPrompts() {
    }

    static final String CRITERIA = """


            ---
            这一轮先不要开始做。先把上面任务说明（和确认表）里的「交付物」和「验收标准」整理成一份可以逐条核对的清单，\
            做完后平台会按这份清单独立检查。照下面的格式输出，不要别的内容：

            ## 交付物
            - 交付物名称 | 类型
            （类型写文件扩展名，如 md、srt、docx、pptx、xlsx、csv、json、png、mp4；不交文件、只在回复里给结论的写「回复」）

            ## 验收标准
            1. 一条能逐条核对的标准
            2. …

            标准写得含糊（比如只写了「做完」「写好」）时，你自己补全成具体、能核对的条目，并在这条开头写 [补全]。\
            标准要能从产出本身判断，不要写「用户满意」这类没法核对的。说明有歧义、必须用户拍板才能继续时，\
            照任务说明里的规则输出 NEEDS_CONFIRMATION 那一行提问。""";

    static String execute(HarnessState s) {
        return "\n\n---\n清单定好了，现在按它完成任务：\n\n" + checklist(s)
                + "\n产出的文件用 file_write（文本）或 file_save（工作区里生成的文件）交付，文件类型要和交付物一致。"
                + "\n做完在结尾写一段简短的交付总结（产出了什么、怎么做的、要注意什么）。平台会按清单独立检查，不用你自己宣布通过。";
    }

    /** The work goes back after a failed check: what failed, for the agent to put right. */
    static String fix(HarnessState s, CheckRound failed) {
        StringBuilder sb = new StringBuilder("\n\n---\n平台检查（第 ").append(failed.round()).append(" 轮）没有通过，下面这些要改：\n");
        failed.issues().forEach(i -> sb.append("- ").append(i).append("\n"));
        sb.append("\n改完重新交付（改已交付的文件时带 replace_id 覆盖，不要另存一份），结尾照样写交付总结。完整清单：\n\n")
                .append(checklist(s));
        return sb.toString();
    }

    /**
     * The user's words for another round (after a stop, or a message after delivery), with what the last check found
     * still open when it failed: the user's words come first.
     */
    static String revise(HarnessState s, String hint, CheckRound failed) {
        StringBuilder sb = new StringBuilder("\n\n---\n用户的意见：\n").append(hint).append("\n");
        if (failed != null && !failed.passed() && !failed.issues().isEmpty()) {
            sb.append("\n上一轮检查（第 ").append(failed.round()).append(" 轮）还没通过的：\n");
            failed.issues().forEach(i -> sb.append("- ").append(i).append("\n"));
            sb.append("和用户的意见冲突时，以用户的意见为准。\n");
        }
        return sb.append("\n按这些修改（改已交付的文件时带 replace_id 覆盖），改完重新交付，结尾写交付总结。平台会再按清单检查：\n\n")
                .append(checklist(s)).toString();
    }

    /** For an independent checker that sees only the checklist and what was handed over, never how it came about. */
    static String check(String title, HarnessState s, List<Produced> produced, String reply) {
        StringBuilder sb = new StringBuilder("""
                你是独立检查员。下面是一个任务的验收清单和它实际交付的东西（你看不到它是怎么做出来的）。\
                只根据下面给出的内容判断，不要调用工具，不要修改任何文件。逐条判断是否满足，给出依据（引用产出里的内容或指出缺了什么）。

                输出格式（每条一行，最后一行写结论；全部满足才是 PASS）：
                1. ✅ 依据
                2. ❌ 依据
                VERDICT: PASS 或 VERDICT: FAIL

                """);
        sb.append("## 任务\n").append(title).append("\n\n").append(checklist(s)).append("\n## 交付的文件\n");
        if (produced.isEmpty()) {
            sb.append("（没有文件，检查对象是下面的最终回复）\n");
        }
        int room = ALL_FILES_CHARS;
        for (Produced p : produced) {
            sb.append("\n### ").append(p.name()).append("（").append(p.size()).append(" 字节）\n");
            if (p.text() == null) {
                sb.append("（不是文本文件：平台已核对它存在、类型对、不是空的）\n");
                continue;
            }
            String t = clip(p.text(), Math.max(500, Math.min(FILE_CHARS, room)));
            room -= t.length();
            sb.append("```\n").append(t).append("\n```\n");
        }
        sb.append("\n## 最终回复\n").append(reply == null || reply.isBlank() ? "（空）" : clip(reply, REPLY_CHARS)).append("\n");
        return sb.toString();
    }

    /** Pulls the checklist out of the agent's answer; empty lists when it wrote none. */
    static Checklist parseChecklist(String text) {
        List<Deliverable> deliverables = new ArrayList<>();
        List<Criterion> criteria = new ArrayList<>();
        String section = "";
        for (String line : text == null ? List.<String>of() : text.lines().toList()) {
            String l = line.strip();
            if (l.startsWith("#")) {
                section = l.contains("交付物") ? "d" : l.contains("验收") || l.contains("标准") ? "c" : "";
                continue;
            }
            Matcher d = DELIVERABLE.matcher(l);
            if (section.equals("d") && d.matches()) {
                String type = d.group(2).toLowerCase(Locale.ROOT);
                deliverables.add(new Deliverable(d.group(1).strip(), type.equals("回复") ? "reply" : type));
                continue;
            }
            Matcher c = NUMBERED.matcher(l);
            if (section.equals("c") && c.matches()) {
                String item = c.group(2).strip();
                boolean added = item.startsWith(ADDED) || item.startsWith("【补全】");
                if (added) {
                    item = item.substring(item.startsWith(ADDED) ? ADDED.length() : "【补全】".length()).strip();
                }
                criteria.add(new Criterion(item, false, null, added));
            }
        }
        return new Checklist(deliverables, criteria);
    }

    record Checklist(List<Deliverable> deliverables, List<Criterion> criteria) {
    }

    /** The checker's answer: each criterion judged (one it skipped counts as not met), its verdict, and what failed. */
    static Review parseReview(String text, List<Criterion> criteria) {
        List<Criterion> judged = new ArrayList<>(criteria.stream().map(c -> c.judged(false, "检查没有给出结论")).toList());
        boolean[] seen = new boolean[criteria.size()];
        String verdict = null;
        for (String line : text == null ? List.<String>of() : text.lines().toList()) {
            Matcher v = VERDICT.matcher(line);
            if (v.find()) {
                verdict = v.group(1).toUpperCase(Locale.ROOT);
                continue;
            }
            Matcher j = JUDGED.matcher(line);
            if (j.matches()) {
                int i = Integer.parseInt(j.group(1)) - 1;
                if (i >= 0 && i < criteria.size()) {
                    judged.set(i, criteria.get(i).judged(j.group(2).equals("✅"), j.group(3).strip()));
                    seen[i] = true;
                }
            }
        }
        List<String> issues = new ArrayList<>();
        for (int i = 0; i < judged.size(); i++) {
            if (!judged.get(i).met()) {
                issues.add("标准 " + (i + 1) + "「" + judged.get(i).text() + "」：" + judged.get(i).evidence());
            }
        }
        if (verdict == null) {
            issues.add("独立检查没有给出 VERDICT 结论，按没通过算");
        }
        boolean passed = "PASS".equals(verdict) && issues.isEmpty();
        return new Review(judged, verdict == null ? "FAIL" : verdict, passed, issues);
    }

    record Review(List<Criterion> criteria, String verdict, boolean passed, List<String> issues) {
    }

    /** Whether the agent stopped to ask the user (a line starting with the marker). */
    static boolean asks(String text) {
        return text != null && text.lines().anyMatch(l -> l.stripLeading().startsWith(CONFIRM));
    }

    /** The question, from the marker on, without the marker itself. */
    static String question(String text) {
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).stripLeading();
            if (l.startsWith(CONFIRM)) {
                String first = l.substring(CONFIRM.length()).replaceFirst("^\\s*[:：]\\s*", "");
                List<String> rest = lines.subList(i + 1, lines.size());
                return (first + (rest.isEmpty() ? "" : "\n" + String.join("\n", rest))).strip();
            }
        }
        return text.strip();
    }

    /** A short yes (「确认交付」「可以」); anything with a no in it (「先别交付」「不确认」) is the user's words for another round. */
    public static boolean approves(String reply) {
        if (reply == null) {
            return false;
        }
        String r = reply.strip().toLowerCase(Locale.ROOT).replaceAll("[。.!！,，\\s]", "");
        String yes = r.replace("没问题", "ok");
        if (yes.matches(".*(不|别|没|未|勿|甭|再改|修改|don't|dont|not|wait).*") || yes.equals("no")) {
            return false;
        }
        return r.length() <= 12 && yes.matches(".*(确认|交付|同意|可以|ok|好的|approve|yes|lgtm).*");
    }

    /** What the user is asked when the rounds ran out. */
    static String stuck(HarnessState s, List<String> issues) {
        StringBuilder sb = new StringBuilder("检查了 ").append(s.round).append(" 轮还有问题没解决：\n");
        issues.forEach(i -> sb.append("- ").append(i).append("\n"));
        return sb.append("\n可以回复修改意见再改一轮，或者照现在的样子交付。\n\n👉 下一步：回复「确认交付」或「停止任务」").toString();
    }

    /** The delivered result: the agent's summary, the files, and how it was checked. */
    static String output(Task task, HarnessState s, List<Produced> produced) {
        StringBuilder sb = new StringBuilder("## ").append(task.title()).append("\n\n")
                .append(s.reply == null || s.reply.isBlank() ? "已完成" : s.reply.strip()).append("\n");
        if (!produced.isEmpty()) {
            sb.append("\n### 产出文件\n");
            produced.forEach(p -> sb.append("- ").append(p.name()).append("\n"));
        }
        CheckRound last = s.checks.isEmpty() ? null : s.checks.get(s.checks.size() - 1);
        if (last != null) {
            long ok = last.platform().stream().filter(HarnessState.PlatformCheck::passed).count();
            sb.append("\n### 检查\n第 ").append(last.round()).append(" 轮").append(last.passed() ? "通过" : "未通过")
                    .append(" · 平台核对 ").append(ok).append("/").append(last.platform().size())
                    .append(last.verdict() == null ? "" : " · 独立检查 " + last.verdict()
                            + (last.checker() == null ? "" : "（" + last.checker() + "）")).append("\n");
            if (!last.passed()) {
                last.issues().forEach(i -> sb.append("- ⚠️ ").append(i).append("\n"));
            }
        }
        return sb.toString();
    }

    static String checklist(HarnessState s) {
        StringBuilder sb = new StringBuilder();
        if (!s.deliverables.isEmpty()) {
            sb.append("## 交付物\n");
            s.deliverables.forEach(d -> sb.append("- ").append(d.name()).append(" | ")
                    .append(d.type().equals("reply") ? "回复" : d.type()).append("\n"));
            sb.append("\n");
        }
        sb.append("## 验收标准\n");
        for (int i = 0; i < s.criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(s.criteria.get(i).text()).append("\n");
        }
        return sb.toString();
    }

    static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n…（后面还有内容，已截断）";
    }
}
