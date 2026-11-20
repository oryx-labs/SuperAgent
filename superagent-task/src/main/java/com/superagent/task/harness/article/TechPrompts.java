package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article;
import com.superagent.task.harness.HarnessState.Article.Issue;
import com.superagent.task.harness.HarnessState.Article.Item;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a technical genre's investigation and its three technical reviewers are told, and how their answers are read. */
final class TechPrompts {

    private static final Pattern OPEN_QUESTION = Pattern.compile("^\\s*[-*•]\\s*(.+?)(?:\\s*[|｜]\\s*(.+))?$");
    private static final Pattern CAN_START = Pattern.compile("能否直接开工\\s*[:：]\\s*(不能|能)(.*)");

    private TechPrompts() {
    }

    // --- research: investigate ---

    static String investigate(Article a, Path repo, boolean more) {
        StringBuilder sb = new StringBuilder("\n\n---\n需求定好了：\n").append(ArticlePrompts.brief(a.brief));
        if (more) {
            sb.append("\n评审发现证据不够，下面这些说法核实不了：\n");
            a.pending.forEach(i -> sb.append("- ").append(i.text()).append("\n"));
            sb.append("\n补充调研，新证据接着编号，输出完整的证据日志（保留原来仍然有效的条目）。\n");
        }
        sb.append("\n现在做调研，这一轮不要写正文。").append(repo == null ? "" : "代码仓库在 " + repo + "，只读。").append("""

                1. 任务说明里给的文件 id 用 file_get 取到工作区，链接用 read_link 读；要读的代码和文档自己去找。
                2. 只用只读命令：cat、head、tail、grep、rg、ls、find、wc、git log / show / diff / blame / grep 等。不要改任何文件，不要跑构建和测试，不要联网下载，不要读仓库以外的文件。
                3. 每个发现记成一条证据，编号 E1、E2……；命令的输出原样记下；数字必须是你量到的，写清命令和环境（版本、机器、数据量）。
                4. 代码引用写成 path:行号 或 path:起-止，路径从仓库根算起，行号要核对过。
                5. 查不到的，写进「未覆盖」，不要用常识补。

                照下面的格式输出：

                ## 证据日志
                ### E1 | 代码 | path/to/File.java:120-135
                看到什么：一两句话

                ### E2 | 命令 | grep -rn "TenantContext.require" superagent-task/src/main | wc -l
                输出：
                ```
                14
                ```
                说明：…

                ### E3 | 数字 | 一句话写这个数字（如 列表查询 85 ms）
                环境：版本、机器、数据量、第几次运行
                来源：E2

                ### E4 | 文档 | 标题 | 版本 | https://链接
                摘录：…

                ## 未覆盖
                - 没查到或没验证的事

                ## 核心判断
                一句话

                说明有歧义、必须用户拍板才能继续时，单独一行以 NEEDS_CONFIRMATION 开头提问。""");
        return sb.append(ArticlePrompts.guide(a)).toString();
    }

    static String judgement(String text) {
        boolean on = false;
        StringBuilder out = new StringBuilder();
        for (String line : (text == null ? "" : text).lines().toList()) {
            String l = line.strip();
            if (l.startsWith("#")) {
                on = l.contains("判断");
            } else if (on && !l.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(l);
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    static String evidenceAsk(List<String> problems) {
        StringBuilder sb = new StringBuilder("证据日志还有这些问题：\n");
        problems.forEach(p -> sb.append("- ").append(p).append("\n"));
        return sb.append("\n👉 下一步：回复「继续」带着这些问题往下写，或直接写补充说明").toString();
    }

    // --- review ---

    static final String ARCHITECT = "你是这份技术文档的架构评审，独立评审（你看不到作者是怎么写的）。需要核对时，可以只读地查看仓库（只用只读命令，不修改任何文件）；"
            + "除此以外只根据下面给出的内容判断。除了 8 个维度，还要特别看：方案和现状是否一致、备选方案是不是稻草人、风险有没有漏、代码和数字的说法是否站得住。";

    static String architect(Article a, String draft, List<Issue> checks, List<Article.Response> declined, Path repo) {
        return ArticlePrompts.editor(a, draft, checks, declined, ARCHITECT + (repo == null ? "" : "仓库在 " + repo + "。"))
                + "\n## 证据日志\n" + ArticlePrompts.clip(EvidenceLog.render(a.evidence), 30_000) + "\n";
    }

    /** The verifier reads the evidence log and the lines the code references point at: the document's claims against those. */
    static String verifier(Article a, String draft, Map<String, String> material) {
        return ArticlePrompts.facts(a, draft, material, a.untraced).replace("你是事实核查员。", "你是复核员（核查技术文档的说法有没有证据支撑）。")
                .replace("「依据」必须原样摘抄资料里的一段话", "「资料编号」写证据编号（E3），「依据」必须原样摘抄证据日志或代码片段里的一段话");
    }

    static String implementer(Article a, String draft) {
        boolean design = a.pack != null && "tech-design".equals(a.pack.genre());
        return "你是要" + (design ? "照这份文档动手实现" : "据这份文档拍板") + "的" + (design ? "工程师" : "负责人") + "，只读下面的稿件，不要调用工具，不要修改任何文件。"
                + "读完回答：我能不能直接开始？开始之前有哪些问题必须先问作者？\n\n输出格式：\n\n能否直接开工：能 或 不能（一句话理由）\n\n## 待澄清问题\n"
                + "- 开工前必须问作者的问题 | 位置（小节标题或原句）\n没有就写「- 无」。只列真正挡着你开工的问题，不要列锦上添花的建议。\n\n## 稿件\n"
                + ArticlePrompts.clip(draft, 60_000) + "\n";
    }

    /** {@code can}: whether the implementer says they could start; {@code questions}: the open questions. */
    record Implementer(Boolean can, String why, List<Item> questions) {
    }

    static Implementer parseImplementer(String text) {
        Boolean can = null;
        String why = null;
        List<Item> questions = new ArrayList<>();
        boolean on = false;
        for (String line : (text == null ? "" : text).lines().toList()) {
            String l = line.strip();
            Matcher c = CAN_START.matcher(l);
            if (c.find()) {
                can = c.group(1).equals("能");
                why = c.group(2).replaceFirst("^[\\s：:（(，,]+", "").replaceFirst("[）)]\\s*$", "");
            } else if (l.startsWith("#")) {
                on = l.contains("待澄清") || l.contains("问题");
            } else if (on) {
                Matcher q = OPEN_QUESTION.matcher(l);
                if (q.matches() && !q.group(1).strip().equals("无")) {
                    questions.add(new Item(null, "一般", "论证", "实现者的疑问：" + q.group(1).strip(), q.group(2), "实现者", false));
                }
            }
        }
        return new Implementer(can, why, questions);
    }

    /**
     * A few open questions are only noted (建议); more than {@code max} fail the review: each one then has to be answered
     * in the document, and a 严重 item says why.
     */
    static List<Item> implementerItems(Implementer im, int max) {
        List<Item> out = new ArrayList<>();
        boolean many = im.questions().size() > max;
        for (Item q : im.questions()) {
            out.add(new Item(null, many ? "一般" : "建议", q.type(), q.text(), q.where(), q.from(), false));
        }
        if (many) {
            out.add(new Item(null, "严重", "论证", "实现者读完有 " + im.questions().size() + " 个必须先问作者的问题，超过 " + max
                    + " 个：文档还没写到能直接开工，逐个补进文档里", null, "实现者", false));
        } else if (Boolean.FALSE.equals(im.can()) && im.questions().isEmpty()) {
            out.add(new Item(null, "严重", "论证", "实现者说不能直接开工：" + (im.why() == null ? "没有给理由" : im.why()), null, "实现者", false));
        }
        return out;
    }
}
