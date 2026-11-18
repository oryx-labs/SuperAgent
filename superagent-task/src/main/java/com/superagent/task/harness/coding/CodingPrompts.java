package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the coding flow says to the agent at each step, and how it reads the answers it needs to check. */
final class CodingPrompts {

    private CodingPrompts() {
    }

    static String spec(String title, String brief, HarnessState s, String revision) {
        String base = """
                你在一个编码任务里，当前是【规格】这一步：先读懂代码，再写出这个任务的规格。这一步只读不改，不要编辑任何文件。

                你的工作目录是这个任务专用的 git 工作区（分支 %s，基于 %s 的 %s）。先看仓库根目录的 CLAUDE.md、AGENTS.md、README 等说明，找到相关代码。

                任务：%s
                说明：
                %s

                按下面的格式回复规格（用这几个二级标题，不要加别的标题）：

                ## 目标
                一两段话：做完后什么变了。

                ## 不做
                明确不在范围内的事（没有就写「无」）。

                ## 验收标准
                1. 每条都是可以检查的结果，写清怎么判断做到了（测试、命令输出、页面行为……）。
                2. ……

                ## 验证命令
                ```
                每行一条在仓库根目录执行、退出码为 0 才算通过的命令，例如 mvn -o -q test、npm test、node --check path/to/file.js
                ```
                验证命令优先用仓库说明（CLAUDE.md / AGENTS.md）里写的构建和测试命令；没写就按项目类型选（Maven、npm、go test……）。只写这台机器上能跑的命令，不要写需要网络部署或人工操作的命令。

                ## 实现思路
                要改哪些文件、怎么改，简短列出。
                """.formatted(s.branch, s.baseBranch, shortSha(s.baseSha), title, brief == null ? "" : brief);
        return revision == null ? base
                : base + "\n\n用户看了上一版规格后的意见（按它修改，整份重新输出）：\n" + revision;
    }

    static final String SPEC_RETRY = """
            上一版缺少可检查的「## 验收标准」（编号列表）或「## 验证命令」（代码块里每行一条命令）。请按格式把整份规格重新输出一遍。""";

    static String develop(HarnessState s) {
        return """
                规格已经确认。现在是【开发】这一步：在当前工作区按规格实现。

                - 只在这个工作区里改，不要切换分支、不要动 %s 分支、不要推送。
                - 每完成一块就 git commit（提交信息写清做了什么）；结束时没提交的改动平台会替你提交。
                - 写完后自己先跑一遍验证命令，失败就修：
                %s
                - 完成后简短说明：改了哪些文件、各做了什么、自己跑验证的结果。不要说「已全部完成」之类的话，平台会自己跑验证来判断。
                """.formatted(s.baseBranch, commands(s));
    }

    static String fix(HarnessState s, HarnessState.Round round) {
        StringBuilder failing = new StringBuilder();
        for (HarnessState.Check c : round.checks()) {
            if (c.exitCode() != 0) {
                failing.append("\n命令：").append(c.command()).append("\n退出码：").append(c.exitCode())
                        .append("\n输出末尾：\n").append(c.logTail()).append("\n");
            }
        }
        return """
                平台在工作区里跑了验证命令（第 %d 轮），下面这些没有通过。找到原因并修好代码（不要改验证命令、不要删测试来让它通过），\
                修完自己再跑一遍，然后用一两句话说明改了什么。
                %s""".formatted(round.round(), failing);
    }

    static String review(String title, HarnessState s, String diff) {
        StringBuilder criteria = new StringBuilder();
        for (int i = 0; i < s.criteria.size(); i++) {
            criteria.append(i + 1).append(". ").append(s.criteria.get(i).text()).append("\n");
        }
        HarnessState.Round last = s.rounds.isEmpty() ? null : s.rounds.get(s.rounds.size() - 1);
        return """
                你是这个编码任务的独立审查人。开发过程你没有参与，只根据规格、改动和验证结果判断。这一步只读不改，不要编辑文件。
                工作目录里就是改完的代码（分支 %s），需要时可以读文件、跑命令核对。

                任务：%s

                规格：
                %s

                验收标准：
                %s
                验证结果：%s

                改动（git diff %s..HEAD）：
                ```diff
                %s
                ```

                按下面的格式回复：

                ## 验收
                每条验收标准一行：`验收 N：满足 | 证据` 或 `验收 N：不满足 | 原因`

                ## 问题
                每个问题一行：`- [严重] 描述 | 文件:行号`（严重 = 有 bug、不满足规格、破坏已有功能或有安全问题；一般 = 应该改但不影响正确性；建议 = 可改可不改）。没有问题就写「无」。

                ## 结论
                `结论：通过` 或 `结论：需修改`
                """.formatted(s.branch, title, s.spec, criteria, last == null ? "（没有）"
                : (last.passed() ? "第 " + last.round() + " 轮全部通过" : "第 " + last.round() + " 轮有失败"),
                shortSha(s.baseSha), diff);
    }

    static String fixReview(List<HarnessState.Finding> findings) {
        StringBuilder sb = new StringBuilder("独立审查发现了下面这些问题，请修好（建议级的可以不改），修完自己跑一遍验证命令，然后简短说明改了什么：\n");
        for (HarnessState.Finding f : findings) {
            sb.append("- [").append(label(f.severity())).append("] ").append(f.text());
            if (f.where() != null && !f.where().isBlank()) {
                sb.append("（").append(f.where()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // --- reading the answers ---

    record Spec(String text, List<String> criteria, List<String> commands) {
        boolean usable() {
            return !criteria.isEmpty() && !commands.isEmpty();
        }
    }

    static Spec parseSpec(String text) {
        String t = text == null ? "" : text.strip();
        List<String> criteria = new ArrayList<>();
        for (String line : section(t, "验收标准").lines().toList()) {
            Matcher m = Pattern.compile("^\\s*(?:\\d+[.、)）]|[-*])\\s*(.+)$").matcher(line);
            if (m.find() && !m.group(1).isBlank()) {
                criteria.add(m.group(1).strip());
            }
        }
        List<String> commands = new ArrayList<>();
        String cmds = section(t, "验证命令");
        Matcher block = Pattern.compile("```[\\w-]*\\n(.*?)```", Pattern.DOTALL).matcher(cmds);
        String body = block.find() ? block.group(1) : cmds;
        for (String line : body.lines().toList()) {
            String c = line.strip().replaceFirst("^[-*]\\s+", "").replaceFirst("^\\$\\s+", "");
            if (!c.isEmpty() && !c.startsWith("#") && !c.startsWith("每行")) {
                commands.add(c.replaceAll("^`|`$", ""));
            }
        }
        return new Spec(t, criteria, commands);
    }

    record Review(List<HarnessState.Finding> findings, List<HarnessState.Criterion> criteria, boolean passed) {
    }

    static Review parseReview(String text, List<HarnessState.Criterion> criteria) {
        String t = text == null ? "" : text;
        List<HarnessState.Finding> findings = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s*[-*]?\\s*\\[(严重|一般|建议)]\\s*(.+?)\\s*(?:\\|\\s*(.+?))?\\s*$").matcher(t);
        while (m.find()) {
            findings.add(new HarnessState.Finding(severity(m.group(1)), m.group(2).strip(),
                    m.group(3) == null ? null : m.group(3).strip(), "open"));
        }
        List<HarnessState.Criterion> checked = new ArrayList<>(criteria);
        Matcher c = Pattern.compile("(?m)验收\\s*(\\d+)\\s*[：:]\\s*(满足|不满足)\\s*(?:\\|\\s*(.+?))?\\s*$").matcher(t);
        while (c.find()) {
            int i = Integer.parseInt(c.group(1)) - 1;
            if (i >= 0 && i < checked.size()) {
                checked.set(i, new HarnessState.Criterion(checked.get(i).text(), c.group(2).equals("满足"),
                        c.group(3) == null ? null : c.group(3).strip()));
            }
        }
        boolean blocking = findings.stream().anyMatch(f -> f.severity().equals("high"));
        boolean passed = !blocking && t.contains("结论：通过") || (!blocking && t.contains("结论:通过"));
        return new Review(findings, checked, passed && checked.stream().allMatch(HarnessState.Criterion::met));
    }

    /** "确认" and its kin, or the confirm button's text, as an approval; anything else is feedback. */
    static boolean approves(String reply) {
        if (reply == null) {
            return false;
        }
        String r = reply.strip().toLowerCase(Locale.ROOT).replaceAll("[。.!！\\s]", "");
        return r.length() <= 12 && r.matches(".*(确认|继续|同意|可以|没问题|开始|ok|好的|approve|yes|lgtm).*");
    }

    static String section(String text, String heading) {
        Matcher m = Pattern.compile("(?ms)^#{1,3}\\s*" + heading + "\\s*$(.*?)(?=^#{1,3}\\s|\\z)").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String commands(HarnessState s) {
        StringBuilder sb = new StringBuilder();
        s.verifyCommands.forEach(c -> sb.append("  ").append(c).append("\n"));
        return sb.toString().stripTrailing();
    }

    static String severity(String label) {
        return switch (label) {
            case "严重" -> "high";
            case "一般" -> "medium";
            default -> "low";
        };
    }

    static String label(String severity) {
        return switch (severity) {
            case "high" -> "严重";
            case "medium" -> "一般";
            default -> "建议";
        };
    }

    static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
    }
}
