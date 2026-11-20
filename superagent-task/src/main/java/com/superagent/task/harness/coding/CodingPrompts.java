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

    /** How much of a failing command's output goes back to a model: the end is where it says what broke. */
    static final int LOG_CHARS = 1500;

    /** Keeps the conversation (and so every later turn's cost) from growing on tool output nobody needs. */
    static final String TOOL_USE = """
            控制上下文，别让工具输出撑大对话：
            - 构建和测试用安静模式，只看结尾和失败部分，如 `mvn -q ...`，或加 `| tail -n 80`、`| grep -E "FAIL|ERROR"` 过滤；
            - 读大文件用 grep 或指定行范围，不要整页读；
            - 已经读过、也没改过的文件不要重复读。""";

    static String develop(HarnessState s) {
        return """
                规格已经确认。现在是【开发】这一步：在当前工作区按规格实现。

                - 只在这个工作区里改，不要切换分支、不要动 %s 分支、不要推送。
                - 每完成一块就 git commit（提交信息写清做了什么）；结束时没提交的改动平台会替你提交。
                - 结束前先对照规格的验收标准逐条自查，每条都要做到；做不到就继续改，别带着缺口交付。
                - 自查之后再自己跑一遍验证命令，失败就修：
                %s
                - 完成后写交付说明（后面验收人会读它）：先逐条列出验收标准 `N. 怎么做到的 | 证据`（文件、测试名、验证输出的哪一段），\
                再写改了哪些文件、自己跑验证的结果。不要说「已全部完成」之类的话，平台会自己跑验证来判断。

                %s
                """.formatted(s.baseBranch, commands(s), TOOL_USE);
    }

    /** Only the hand-over note was wanting: write it again, touching nothing else. */
    static String fixNotes(List<HarnessState.Finding> issues) {
        StringBuilder sb = new StringBuilder("验收认为代码没问题，只是交付说明缺了下面这些内容：\n");
        issues.forEach(f -> sb.append("- ").append(ReviewLoop.tag(f.id())).append(" ").append(f.text())
                .append(f.where() == null || f.where().isBlank() ? "" : "（" + f.where() + "）").append("\n"));
        return sb.append("""

                这一步只补写交付说明：不要改任何文件，不要跑构建或测试，也不要提交。按开发时的格式重写完整的交付说明\
                （逐条验收标准 `N. 怎么做到的 | 证据`，改了哪些文件，验证结果，以及上面缺的内容）。\
                需要的事实从已有的改动和平台的验证输出里找，不要凭空编。""").toString();
    }

    static String fix(HarnessState s, HarnessState.Round round) {
        StringBuilder failing = new StringBuilder();
        for (HarnessState.Check c : round.checks()) {
            if (c.exitCode() != 0) {
                failing.append("\n命令：").append(c.command()).append("\n退出码：").append(c.exitCode())
                        .append("\n输出末尾：\n").append(Git.tail(c.logTail(), LOG_CHARS)).append("\n");
            }
        }
        return """
                平台在工作区里跑了验证命令（第 %d 轮），下面这些没有通过。找到原因并修好代码（不要改验证命令、不要删测试来让它通过），\
                修完自己再跑一遍，然后用一两句话说明改了什么。
                %s
                %s""".formatted(round.round(), failing, TOOL_USE);
    }

    private static final String FOCUS = """
            只找会真正出问题的地方：bug、不满足规格或需求、破坏已有功能、安全问题、数据丢失或错乱。\
            不要过度审查：代码风格、命名、注释、可读性、「可以写得更好」都不算问题，最多写成「建议」；\
            没有就是没有，不要为了显得仔细而凑问题。""";

    private static final String READ_ONLY = """
            这一步只读：不要编辑、新建或删除文件，不要 git commit；需要时可以读文件、跑不改文件的命令核对。\
            你做的任何改动平台都会撤销。""";

    static String review(String title, HarnessState s, String diff, int round, List<HarnessState.Finding> previous) {
        StringBuilder carried = new StringBuilder();
        if (!previous.isEmpty()) {
            carried.append("\n## 上一轮交回修复的问题\n开发说已经修了；核对它们是否真的修好。还在的沿用原编号写进问题列表，修好了的不要再写。\n");
            previous.forEach(f -> carried.append("- [").append(label(f.severity())).append("] ").append(ReviewLoop.tag(f.id()))
                    .append(" ").append(f.text()).append(f.where() == null ? "" : " | " + f.where()).append("\n"));
        }
        if (!s.pendingRebuttals.isEmpty()) {
            carried.append("\n## 开发不同意的问题\n开发对下面这些问题提出了反对意见。逐条裁定：理由成立写「接受」（这个问题以后不再算），不成立写「驳回」并说明为什么。\n");
            s.pendingRebuttals.forEach(r -> carried.append("- ").append(ReviewLoop.tag(r.id())).append("：").append(r.reason()).append("\n"));
        }
        HarnessState.Round last = s.rounds.isEmpty() ? null : s.rounds.get(s.rounds.size() - 1);
        return """
                你是这个编码任务的独立审查人（第 %d 轮）。代码是另一个模型写的，过程你没有参与，只根据规格、改动和验证结果判断。
                %s
                工作目录里就是改完的代码（分支 %s）。

                %s

                任务：%s

                规格：
                %s

                验证结果（平台自己跑的）：%s

                改动（git diff %s..HEAD）：
                ```diff
                %s
                ```
                %s
                按下面的格式回复：

                ## 问题
                每个问题一行：`- [严重] #编号 描述 | 文件:行号`。上一轮的问题还在就沿用它的编号，新问题不写编号。
                严重 = 会出错、不满足规格、破坏已有功能、安全或数据问题；一般 = 确实会出错但影响面小；建议 = 不影响正确性，可改可不改（不会让开发返工）。
                没有问题就写「无」。

                ## 裁定
                （有「开发不同意的问题」时）每条一行：`裁定 #编号：接受 | 理由` 或 `裁定 #编号：驳回 | 理由`

                ## 结论
                最后单独一行：没有严重和一般问题写 `VERDICT: PASS`，否则写 `VERDICT: FAIL`。
                """.formatted(round, READ_ONLY, s.branch, FOCUS, title, s.spec,
                last == null ? "（没有）" : (last.passed() ? "第 " + last.round() + " 轮全部通过" : "第 " + last.round() + " 轮有失败"),
                shortSha(s.baseSha), diff, carried);
    }

    static String accept(String title, HarnessState s, String diff, int round) {
        StringBuilder criteria = new StringBuilder();
        for (int i = 0; i < s.criteria.size(); i++) {
            criteria.append(i + 1).append(". ").append(s.criteria.get(i).text()).append("\n");
        }
        StringBuilder checks = new StringBuilder();
        HarnessState.Round last = s.rounds.isEmpty() ? null : s.rounds.get(s.rounds.size() - 1);
        if (last != null) {
            for (HarnessState.Check c : last.checks()) {
                checks.append("\n命令：").append(c.command()).append(" → 退出码 ").append(c.exitCode())
                        .append("\n输出末尾：\n").append(Git.tail(c.logTail(), LOG_CHARS)).append("\n");
            }
        }
        return """
                你是这个编码任务的验收人（第 %d 轮）。代码是另一个模型写的，已经通过了平台的验证和代码审查。\
                现在对照规格里的验收标准逐条核对：每条是否真的做到了。
                %s
                工作目录里就是改完的代码（分支 %s）。

                只判断标准有没有做到，不评价写法。没做到、或找不到依据的，就是 ❌。

                任务：%s

                规格：
                %s

                验收标准：
                %s
                开发的交付说明：
                %s

                平台第 %s 轮验证的输出：
                %s

                改动（git diff %s..HEAD）：
                ```diff
                %s
                ```

                按下面的格式回复：

                ## 逐条核对
                每条标准一行，编号和上面一致：`N. ✅ | 依据` 或 `N. ❌ | 类别 | 原因`。依据要具体：哪个文件、哪个测试、验证输出里哪一段能看到。
                ❌ 的类别二选一：`说明缺失`（交付说明、实测结论、检查结果这类说明性的东西没写，代码本身没问题）、\
                `实现问题`（代码或行为不符合要求）。拿不准就写 `实现问题`。
                
                ## 结论
                最后单独一行：全部 ✅ 写 `VERDICT: PASS`，否则写 `VERDICT: FAIL`。
                """.formatted(round, READ_ONLY, s.branch, title, s.spec, criteria, blankAs(s.handoff), last == null ? "?" : last.round(),
                checks.isEmpty() ? "（没有）" : checks, shortSha(s.baseSha), diff);
    }

    static String fixReview(List<HarnessState.Finding> findings, String from, String feedback) {
        StringBuilder sb = new StringBuilder(from).append("发现了下面这些问题，请在当前工作区里修好，修完自己跑一遍验证命令：\n");
        for (HarnessState.Finding f : findings) {
            sb.append("- [").append(label(f.severity())).append("] ").append(ReviewLoop.tag(f.id())).append(" ").append(f.text());
            if (f.where() != null && !f.where().isBlank()) {
                sb.append("（").append(f.where()).append("）");
            }
            sb.append("\n");
        }
        if (feedback != null) {
            sb.append("\n用户的修改意见（优先照这个改）：\n").append(feedback).append("\n");
        }
        return sb.append("""

                认为某条不是问题、不该改的，可以不改，但要说明理由，审查人会裁定。最后按编号逐条回复，每条一行：
                `问题 #编号：已修 | 改了什么` 或 `问题 #编号：不同意 | 理由`
                （验收标准的编号写成 `问题 验收 N`）

                """ + TOOL_USE).toString();
    }

    /** The marker a resolver writes when the two sides want contradictory things: the user decides, not the model. */
    static final String INTENT_CLASH = "CONFLICT: INTENT";

    static String resolve(String title, HarnessState s, Workspace.Conflict c, String feedback, int round) {
        StringBuilder hunks = new StringBuilder();
        c.hunks().forEach((f, h) -> hunks.append("\n### ").append(f).append("\n```\n").append(h).append("```\n"));
        return """
                现在是【解决冲突】这一步（第 %d 轮）。任务做完了，要合并回 %s；这期间 %s 上有了新的提交，平台把你的分支变基上去时，\
                提交「%s」和它们碰到了同一处，变基停在这里。

                在当前工作区里把下面这些文件的冲突解决掉：%s

                要求：
                - 先读懂两边各自想做什么（下面有双方的提交说明，需要时用 git show / git log 看具体改动），在保留双方功能的前提下合并。\
                不能简单地整块选「ours」或「theirs」把一方覆盖掉。
                - 解决后文件里不能再有 <<<<<<<、=======、>>>>>>> 冲突标记。
                - 只改冲突相关的地方；不要执行 git rebase --continue / --abort / --skip，不要 git commit、不要切分支，这些由平台来做。
                - 可以跑验证命令确认没改坏：
                %s
                - 如果两边的意图本身互相矛盾（比如同一个字段被改成两种不同的含义），不要硬合：单独一行写 `%s`，\
                然后说明是哪两处改动冲突、各自想达到什么、你推荐怎么处理，等用户决定。
                - 完成后用几句话说明每个文件是怎么合的。

                任务：%s

                %s 这边新增的提交：
                %s

                本任务的提交：
                %s

                冲突片段（<<<<<<< 到 ======= 是 %s 这边，======= 到 >>>>>>> 是本任务这边）：
                %s
                规格：
                %s
                """.formatted(round, s.baseBranch, s.baseBranch, c.commit(), String.join("、", c.files()), commands(s),
                INTENT_CLASH, title, s.baseBranch, blankAs(c.baseLog()), blankAs(c.taskLog()), s.baseBranch, hunks,
                s.spec == null ? "（没有）" : s.spec)
                + guidance(s, feedback);
    }

    /** After the rebase went through, the checks found something: fix it in the worktree (no rebase under way now). */
    static String resolveFix(HarnessState s, String feedback, int round) {
        return """
                【解决冲突】第 %d 轮：变基已经完成，但平台检查解决结果时没有通过。请在当前工作区里修好\
                （不要改验证命令、不要删测试），修完自己跑一遍验证命令，然后用几句话说明改了什么。
                如果发现两边的意图本身互相矛盾，单独一行写 `%s`，说明是哪两处冲突、推荐怎么处理。
                """.formatted(round, INTENT_CLASH) + guidance(s, feedback);
    }

    private static String guidance(HarnessState s, String feedback) {
        StringBuilder sb = new StringBuilder();
        if (feedback != null) {
            sb.append("\n上一轮没有通过的原因（这一轮要解决）：\n").append(feedback).append("\n");
        }
        if (s.conflictHint != null) {
            sb.append("\n用户对解决冲突的指示（优先照这个做）：\n").append(s.conflictHint).append("\n");
        }
        return sb.toString();
    }

    static String resolveReview(String title, HarnessState s, List<String> files, String snippets, String diff) {
        return """
                你是独立审查人，只看这次【解决冲突】做得对不对。背景：编码任务「%s」要合并回 %s，变基时和 %s 上新提交的改动碰到了同一处，\
                另一个模型解决了冲突。工作目录里就是解决后的代码。
                %s
                只回答一件事：冲突文件（%s）里，%s 那边和本任务这边的功能是不是都保留了，有没有一方被整块覆盖、有没有合坏（逻辑错、重复、漏掉）。\
                不评价写法，不审查和冲突无关的代码，不要过度审查；只报严重或一般（会出 bug）的问题。

                冲突时的片段（<<<<<<< 到 ======= 是 %s 这边，======= 到 >>>>>>> 是本任务这边）：
                %s
                解决后，本任务在这些文件上相对 %s 最新提交的改动：
                ```diff
                %s
                ```

                按下面的格式回复：

                ## 问题
                每个问题一行：`- [严重] 描述 | 文件:行号`。没有就写「无」。

                ## 结论
                最后单独一行：没有严重和一般问题写 `VERDICT: PASS`，否则写 `VERDICT: FAIL`。
                """.formatted(title, s.baseBranch, s.baseBranch, READ_ONLY, String.join("、", files), s.baseBranch,
                s.baseBranch, snippets.isBlank() ? "（没有留下）\n" : snippets, s.baseBranch, diff);
    }

    /** Whether the resolver stopped because the two sides contradict each other. */
    static boolean intentClash(String text) {
        return text != null && text.lines().anyMatch(l -> l.strip().replaceAll("[`*]", "").equalsIgnoreCase(INTENT_CLASH));
    }

    private static String blankAs(String s) {
        return s == null || s.isBlank() ? "（没有）" : s;
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
            Matcher m = Pattern.compile("^(\\s*)(?:\\d+[.、)）]|[-*])\\s*(.+)$").matcher(line);
            if (m.find() && !m.group(2).isBlank()) {
                if (!m.group(1).isEmpty() && line.stripLeading().matches("[-*]\\s.*") && !criteria.isEmpty()) {
                    criteria.set(criteria.size() - 1, criteria.get(criteria.size() - 1) + " " + m.group(2).strip()); // a sub-point
                } else {
                    criteria.add(m.group(2).strip());
                }
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

    record Ruling(String id, boolean accepted, String reason) {
    }

    /** {@code verdict}: PASS | FAIL, FAIL when the answer has no VERDICT line. */
    record Review(List<HarnessState.Finding> findings, List<Ruling> rulings, String verdict) {
    }

    private static final Pattern FINDING = Pattern.compile(
            "(?m)^\\s*[-*]?\\s*\\[(严重|一般|建议)]\\s*(?:#(\\w+)\\s*)?(.+?)\\s*(?:\\|\\s*(.+?))?\\s*$");
    private static final Pattern RULING = Pattern.compile("(?m)裁定\\s*(?:#|验收\\s*)?(\\w+)\\s*[：:]\\s*(接受|驳回)\\s*(?:\\|\\s*(.+?))?\\s*$");
    private static final Pattern VERDICT = Pattern.compile("(?mi)^[\\s>*`_-]*VERDICT\\s*[：:]\\s*[*`_]*\\s*(PASS|FAIL)\\b");
    private static final Pattern REBUTTAL = Pattern.compile("(?m)问题\\s*(#|验收\\s*)?(\\w+)\\s*[：:]\\s*不同意\\s*(?:\\|\\s*(.+?))?\\s*$");
    private static final Pattern KIND = Pattern.compile("^[\\[【`*]*(说明缺失|实现问题)[\\]】`*]*\\s*(?:[|｜:：]\\s*)?");
    private static final Pattern CHECKED = Pattern.compile("(?m)^\\s*(?:验收\\s*)?(\\d+)\\s*[.、:：)）]?\\s*(✅|❌)\\s*(?:[|｜]\\s*)?(.*?)\\s*$");

    static Review parseReview(String text) {
        String t = text == null ? "" : text;
        List<HarnessState.Finding> findings = new ArrayList<>();
        Matcher m = FINDING.matcher(t);
        while (m.find()) {
            findings.add(new HarnessState.Finding(severity(m.group(1)), m.group(3).strip(),
                    m.group(4) == null ? null : m.group(4).strip(), "open", m.group(2)));
        }
        List<Ruling> rulings = new ArrayList<>();
        Matcher r = RULING.matcher(t);
        while (r.find()) {
            String id = r.group(0).matches("(?s)裁定\\s*验收.*") ? "AC" + r.group(1) : r.group(1);
            rulings.add(new Ruling(id, r.group(2).equals("接受"), r.group(3) == null ? null : r.group(3).strip()));
        }
        return new Review(findings, rulings, verdict(t));
    }

    /** The last VERDICT line's word; FAIL without one. */
    static String verdict(String text) {
        Matcher v = VERDICT.matcher(text == null ? "" : text);
        String out = "FAIL";
        while (v.find()) {
            out = v.group(1).toUpperCase(Locale.ROOT);
        }
        return out;
    }

    record Acceptance(List<HarnessState.Criterion> criteria, String verdict) {
    }

    /** Each criterion as checked; one the answer skipped counts as not met. */
    static Acceptance parseAccept(String text, List<HarnessState.Criterion> criteria) {
        String t = text == null ? "" : text;
        List<HarnessState.Criterion> checked = new ArrayList<>();
        criteria.forEach(c -> checked.add(new HarnessState.Criterion(c.text(), false, "验收没有核对这一条")));
        Matcher c = CHECKED.matcher(t);
        while (c.find()) {
            int i = Integer.parseInt(c.group(1)) - 1;
            if (i >= 0 && i < checked.size()) {
                String why = c.group(3).replaceFirst("^[|｜]\\s*", "").strip();
                boolean met = c.group(2).equals("✅");
                Matcher k = KIND.matcher(why);
                boolean note = !met && k.find() && k.group(1).equals("说明缺失");
                if (k.reset().find()) {
                    why = why.substring(k.end()).strip();
                }
                checked.set(i, new HarnessState.Criterion(checked.get(i).text(), met, why.isEmpty() ? null : why, false,
                        note ? HarnessState.Criterion.NOTE : null));
            }
        }
        return new Acceptance(checked, verdict(t));
    }

    /** Where the developer disagreed, by issue id (criterion N as {@code ACN}). */
    static List<HarnessState.Rebuttal> parseRebuttals(String text) {
        List<HarnessState.Rebuttal> out = new ArrayList<>();
        Matcher m = REBUTTAL.matcher(text == null ? "" : text);
        while (m.find()) {
            String id = m.group(1) != null && m.group(1).startsWith("验收") ? "AC" + m.group(2) : m.group(2);
            out.add(new HarnessState.Rebuttal(id, m.group(3) == null ? "（没有写理由）" : m.group(3).strip(), null, null));
        }
        return out;
    }

    /** "确认" and its kin, or the confirm button's text, as an approval; anything else is feedback. */
    static boolean approves(String reply) {
        if (reply == null) {
            return false;
        }
        String r = reply.strip().toLowerCase(Locale.ROOT).replaceAll("[。.!！\\s]", "");
        return r.length() <= 12 && r.matches(".*(确认|继续|同意|可以|没问题|开始|ok|好的|approve|yes|lgtm).*");
    }

    /**
     * A reply that only says "go on" ("继续", "确认", "好的，继续"), with nothing else in it; "继续，以 main 为准" is not:
     * the rest of it is an instruction.
     */
    static boolean bareApproval(String reply) {
        if (reply == null) {
            return false;
        }
        String r = reply.strip().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s。，、！？；：…]", "");
        return r.matches("(确认|继续|同意|可以|没问题|开始|好的|好|ok|okay|approve|yes|lgtm|交付|合并|重试|再试|吧|了)+");
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
