package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article;
import com.superagent.task.harness.HarnessState.Article.Brief;
import com.superagent.task.harness.HarnessState.Article.Claim;
import com.superagent.task.harness.HarnessState.Article.Fact;
import com.superagent.task.harness.HarnessState.Article.Issue;
import com.superagent.task.harness.HarnessState.Article.Item;
import com.superagent.task.harness.HarnessState.Article.Response;
import com.superagent.task.harness.HarnessState.Article.Score;
import com.superagent.task.harness.HarnessState.Article.Section;
import com.superagent.task.harness.HarnessState.Article.Source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the writing flow tells the author and the three reviewers, and how it reads their fixed-format answers. */
final class ArticlePrompts {

    static final List<String> GENRES = List.of("公众号", "报告", "技术文章", "讲义");
    /** Each genre's plausible length (characters). */
    static final Map<String, int[]> WORD_RANGE = Map.of("公众号", new int[]{1500, 8000}, "报告", new int[]{2000, 30000},
            "技术文章", new int[]{1500, 15000}, "讲义", new int[]{1000, 20000});
    /** The sections a genre must have (each entry: any one of the names). */
    static final Map<String, List<List<String>>> REQUIRED = Map.of(
            "讲义", List.of(List.of("学习目标", "目标"), List.of("小结", "总结"), List.of("练习", "习题", "作业")),
            "技术文章", List.of(List.of("背景", "问题"), List.of("做法", "方案", "实现", "设计"), List.of("示例", "例子", "实例")),
            "报告", List.of(List.of("摘要", "概述"), List.of("结论", "建议")));
    static final int MIN_SOURCES_DEEP = 3, MIN_SOURCES = 1;
    static final double OUTLINE_BUDGET = 0.15;

    private static final int SOURCE_CHARS = 6_000, ALL_SOURCES_CHARS = 40_000, DRAFT_CHARS = 60_000;
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*(?:[-*•]\\s*)?\\**(文体|读者已知|读者|目的|核心观点|字数|语气|必须用到的资料|不写什么|验收标准)\\**\\s*[:：]\\s*(.*)$");
    private static final Pattern WORDS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([千万kK])?");
    private static final Pattern ROW = Pattern.compile("^\\s*[-*•]\\s*(.+)$");
    private static final Pattern SID = Pattern.compile("[SE]\\d+"); // a source or, in an investigation, an evidence entry
    private static final Pattern SECTION = Pattern.compile("^###\\s+(.+?)\\s*[|｜]\\s*(\\d+)\\s*字?\\s*$");
    private static final Pattern DIMENSION = Pattern.compile(
            "^\\s*(?:[-*]\\s*)?维度\\s*(\\d)\\s*([^：:|｜]*?)\\s*[：:]\\s*(\\d)(?:\\s*分)?\\s*(?:[|｜]\\s*([^|｜]*?)\\s*)?(?:[|｜]\\s*(.*?)\\s*)?$");
    private static final Pattern FINDING = Pattern.compile(
            "^\\s*[-*•]\\s*[\\[【](严重|一般|建议)[\\]】]\\s*[\\[【](结构|论证|表达|规范)[\\]】]\\s*(.+?)(?:\\s*[|｜]\\s*(.+))?$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)\\s*[.、)）]\\s*(.+)$");
    private static final Pattern RESPONSE = Pattern.compile("^\\s*[-*•]?\\s*(R\\d+)\\s*[:：]?\\s*(已改|不改)\\s*(?:[|｜:：]\\s*(.*))?$");

    private ArticlePrompts() {
    }

    // --- ① brief ---

    static final String BRIEF = """


            ---
            这是一个写作任务（长文）。这一轮先不要开始写，也不要读资料：先把上面任务说明（和确认表）整理成一份写作需求，\
            照下面的格式输出，每行一项，不要别的内容：

            文体：公众号 / 报告 / 技术文章 / 讲义（四选一）
            读者：目标读者是谁
            读者已知：他们已经知道什么
            目的：读者读完要能做什么、明白什么
            核心观点：一句话的角度或观点（还定不了就写「待资料后确定」）
            字数：目标字数（只写数字）
            语气：语气和风格（Agent 挂了写作规范 skill 就写它的名字）
            必须用到的资料：任务里给的文件 id 和链接，没有写「无」
            不写什么：明确不写的内容，没有写「无」
            验收标准：确认表里的验收标准

            文体、读者、目的、字数是必填项。说明里推断不出来时，在这一项写「❓ 你建议的值」，平台会请用户确认。\
            说明有歧义、必须用户拍板才能继续时，单独一行以 NEEDS_CONFIRMATION 开头提问。""";

    /** The brief prompt when the task runs by a genre pack: the pack is the genre, and it may be the wrong one. */
    static String briefPrompt(GenrePack pack, String guide) {
        return BRIEF.replace("文体：公众号 / 报告 / 技术文章 / 讲义（四选一）", "文体：" + pack.name())
                + "\n\n这个任务选的文体包是「" + pack.name() + "」（" + (pack.investigates() ? "要读仓库和资料做调研" : "整理给定资料来写")
                + "，字数一般 " + pack.minWords() + "～" + pack.maxWords() + "，必备小节："
                + String.join("、", pack.requiredSections().stream().map(o -> o.get(0)).toList()) + "）。"
                + "读完任务说明后，如果这个文体明显不适合这件事（比如要的是一份调研，选的却是公众号文章），不要硬写：单独一行以 NEEDS_CONFIRMATION 开头，"
                + "说明为什么不合适、建议改用哪个文体。合适就在「文体：」一行照写「" + pack.name() + "」。\n\n## 这个文体包的写法（供你判断，现在不用照着写）\n"
                + ArticlePrompts.clip(guide, 6_000);
    }

    /** The author's guide to the genre, for the steps where the text is shaped. */
    static String guide(Article a) {
        return a.pack == null || a.guide == null ? "" : "\n\n---\n文体包「" + a.pack.name() + "」的写法，按它来写：\n" + a.guide;
    }

    record ParsedBrief(Brief brief, boolean parsed, Map<String, String> suggested, List<String> problems) {
    }

    /** The value of one brief field ({@code 文体}, …) from the author's reply, or null. */
    static String briefField(String text, String name) {
        for (String line : lines(text)) {
            Matcher m = FIELD.matcher(line);
            if (m.matches() && m.group(1).equals(name)) {
                return m.group(2).strip();
            }
        }
        return null;
    }

    /** The brief of a task that runs by a genre pack: the genre is the pack, and its word range is the pack's. */
    static ParsedBrief parseBrief(String text, GenrePack pack) {
        ParsedBrief p = parseBrief(text);
        if (!p.parsed()) {
            return p;
        }
        Map<String, String> suggested = new LinkedHashMap<>(p.suggested());
        suggested.remove("文体");
        List<String> problems = new ArrayList<>();
        Brief b = p.brief();
        if (b.words() <= 0 && !suggested.containsKey("字数")) {
            problems.add("字数「" + words0(text) + "」不是数字");
        } else if (b.words() > 0 && !suggested.containsKey("字数") && (b.words() < pack.minWords() || b.words() > pack.maxWords())) {
            problems.add(pack.name() + "的字数一般在 " + pack.minWords() + "～" + pack.maxWords() + " 字，这里写的是 " + b.words());
        }
        return new ParsedBrief(new Brief(pack.name(), b.audience(), b.knows(), b.purpose(), b.thesis(), b.words(), b.style(), b.mustUse(),
                b.exclude(), b.acceptance()), true, suggested, problems);
    }

    private static String words0(String text) {
        String v = briefField(text, "字数");
        return v == null ? "" : v;
    }

    static ParsedBrief parseBrief(String text) {
        Map<String, String> f = new LinkedHashMap<>();
        for (String line : lines(text)) {
            Matcher m = FIELD.matcher(line);
            if (m.matches()) {
                f.putIfAbsent(m.group(1), m.group(2).strip());
            }
        }
        if (f.isEmpty()) {
            return new ParsedBrief(null, false, Map.of(), List.of("回复里没有找到「文体：」「读者：」这样的字段"));
        }
        Map<String, String> suggested = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String k : List.of("文体", "读者", "目的", "字数")) {
            String v = f.getOrDefault(k, "");
            if (v.startsWith("❓") || v.startsWith("?")) {
                suggested.put(k, v.replaceFirst("^(❓|\\?)\\s*", "").strip());
                f.put(k, suggested.get(k));
            } else if (v.isBlank() || v.equals("无")) {
                suggested.put(k, "");
            }
        }
        String genre = genre(f.get("文体"));
        if (genre == null && !suggested.containsKey("文体")) {
            problems.add("文体「" + f.getOrDefault("文体", "") + "」不是公众号、报告、技术文章、讲义之一");
        }
        int words = words(f.get("字数"));
        if (!suggested.containsKey("字数")) {
            if (words <= 0) {
                problems.add("字数「" + f.getOrDefault("字数", "") + "」不是数字");
            } else if (genre != null) {
                int[] r = WORD_RANGE.get(genre);
                if (words < r[0] || words > r[1]) {
                    problems.add(genre + "的字数一般在 " + r[0] + "～" + r[1] + " 字，这里写的是 " + words);
                }
            }
        }
        Brief b = new Brief(genre == null ? f.get("文体") : genre, f.get("读者"), f.get("读者已知"), f.get("目的"),
                f.get("核心观点"), Math.max(0, words), f.get("语气"), f.get("必须用到的资料"), f.get("不写什么"), f.get("验收标准"));
        return new ParsedBrief(b, true, suggested, problems);
    }

    static String genre(String v) {
        if (v == null) {
            return null;
        }
        if (v.contains("公众号") || v.contains("微信")) {
            return "公众号";
        }
        if (v.contains("报告")) {
            return "报告";
        }
        if (v.contains("技术")) {
            return "技术文章";
        }
        return v.contains("讲义") || v.contains("课程") || v.contains("教程") ? "讲义" : null;
    }

    static int words(String v) {
        Matcher m = WORDS.matcher(v == null ? "" : v.replace(",", ""));
        if (!m.find()) {
            return 0;
        }
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        return (int) Math.round(unit == null ? n : unit.equals("万") ? n * 10_000 : n * 1_000);
    }

    static String briefAsk(Map<String, String> suggested, List<String> problems) {
        StringBuilder sb = new StringBuilder("写作需求还缺这些，请确认：\n");
        suggested.forEach((k, v) -> sb.append("- ").append(k).append("：").append(v.isBlank() ? "（没有建议值，请直接写）" : "建议「" + v + "」")
                .append("\n"));
        problems.forEach(p -> sb.append("- ").append(p).append("\n"));
        return sb.append("\n👉 下一步：回复「确认」采用建议值，或直接写上缺的内容").toString();
    }

    static String retry(String what, List<String> problems, String format) {
        return "\n\n---\n上一份" + what + "平台核对没通过：\n- " + String.join("\n- ", problems)
                + "\n\n照格式重新输出完整的一份。" + format;
    }

    // --- ② research ---

    static String research(Article a) {
        return "\n\n---\n需求定好了：\n" + brief(a.brief) + """

                现在整理资料，这一轮不要写正文：
                1. 任务说明里给的每个文件 id 用 file_get 取到工作区，每个链接用 read_link 读；可以补充检索公开资料，但用到的每一份都要编号。
                2. 把每份资料的原文（读到的全文）存成工作区里的 sources/S1.md、sources/S2.md……，编号和下面的资料清单一致：\
                平台核对数字和事实时只认这些原文。
                3. 读不到的资料不要编内容，在清单里标「读不到」。

                照下面的格式输出：

                ## 资料清单
                - S1 | 标题 | 链接或文件 id | 已读 / 读不到 | 一句话摘要

                ## 主张与证据
                - 可能写进文章的关键事实、数字或引语 | S1,S2 | 已证实 / 推断 / 未知

                ## 核心判断
                一句话

                说明有歧义、必须用户拍板才能继续时，单独一行以 NEEDS_CONFIRMATION 开头提问。""";
    }

    static String researchMore(Article a, List<Item> items) {
        StringBuilder sb = new StringBuilder("\n\n---\n评审发现证据不够，太多说法在资料里核实不了：\n");
        items.forEach(i -> sb.append("- ").append(i.text()).append("\n"));
        return sb.append("\n补充资料（新资料接着编号，原文同样存进 sources/），补完照原来的格式输出完整的资料清单、主张与证据和核心判断。")
                .append("资料里找不到依据的说法，在主张表里标「未知」。").toString();
    }

    record Research(List<Source> sources, List<Claim> claims, String judgement, boolean parsed) {
    }

    static Research parseResearch(String text) {
        List<Source> sources = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        StringBuilder judgement = new StringBuilder();
        String section = "";
        for (String line : lines(text)) {
            String l = line.strip();
            if (l.startsWith("#")) {
                section = l.contains("资料") ? "s" : l.contains("主张") ? "c" : l.contains("判断") ? "j" : "";
                continue;
            }
            Matcher r = ROW.matcher(l);
            if (section.equals("s") && r.matches()) {
                String[] c = cells(r.group(1));
                if (c.length >= 2 && SID.matcher(c[0]).matches()) {
                    sources.add(new Source(c[0], c[1], at(c, 2), at(c, 3).contains("读不到") ? "读不到" : "已读", at(c, 4)));
                }
            } else if (section.equals("c") && r.matches()) {
                String[] c = cells(r.group(1));
                List<String> ids = new ArrayList<>();
                Matcher id = SID.matcher(at(c, 1));
                while (id.find()) {
                    ids.add(id.group());
                }
                String status = at(c, 2);
                claims.add(new Claim(c[0], ids, status.contains("已证实") ? "已证实" : status.contains("推断") ? "推断" : "未知"));
            } else if (section.equals("j") && !l.isEmpty()) {
                judgement.append(judgement.isEmpty() ? "" : " ").append(l);
            }
        }
        return new Research(sources, claims, judgement.isEmpty() ? null : judgement.toString(), !sources.isEmpty() || !claims.isEmpty());
    }

    static List<String> researchProblems(Research r, String genre, boolean materialsGiven) {
        List<String> out = new ArrayList<>();
        List<String> ids = r.sources().stream().map(Source::id).toList();
        for (Claim c : r.claims()) {
            if (c.sources().isEmpty() && !"未知".equals(c.status())) {
                out.add("主张「" + c.text() + "」没有标资料编号");
            }
            c.sources().stream().filter(id -> !ids.contains(id))
                    .forEach(id -> out.add("主张「" + c.text() + "」引用的 " + id + " 不在资料清单里"));
        }
        int min = "报告".equals(genre) || "技术文章".equals(genre) ? MIN_SOURCES_DEEP : MIN_SOURCES;
        long read = r.sources().stream().filter(s -> !"读不到".equals(s.status())).count();
        if (materialsGiven && read < min) {
            out.add(genre + "至少要 " + min + " 份读到的资料，现在只有 " + read + " 份");
        }
        return out;
    }

    static String sourcesAsk(List<String> unreadable, List<String> problems) {
        StringBuilder sb = new StringBuilder();
        if (!unreadable.isEmpty()) {
            sb.append("这些资料读不到：\n");
            unreadable.forEach(u -> sb.append("- ").append(u).append("\n"));
            sb.append("\n可以换一份资料（发文件或链接），或者不用它继续写。\n");
        }
        if (!problems.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("资料整理还有这些问题：\n");
            problems.forEach(p -> sb.append("- ").append(p).append("\n"));
        }
        return sb.append("\n👉 下一步：回复「不用这份资料，继续」或直接发新的资料").toString();
    }

    // --- ③ outline ---

    static String outline(Article a) {
        return "\n\n---\n" + (a.pack != null && a.pack.investigates() ? "调研做完了（" + a.evidence.size() + " 条证据）。每个小节的「证据：」写证据编号（E1, E2）。"
                : "资料整理好了（" + a.sources.size() + " 份资料、" + a.claims.size() + " 条主张）。") + "现在写提纲，这一轮不要写正文。" + OUTLINE_FORMAT
                + requiredHint(a) + guide(a);
    }

    static final String OUTLINE_FORMAT = """
            照下面的格式输出（各节字数加起来要接近目标字数；每节的论点至少引用一条资料编号，没有资料的任务除外）：

            ## 备选标题
            1. …
            2. …

            ## 中心判断
            一句话

            ## 小节
            ### 小节标题 | 字数预算（数字）
            论点：本节要讲清的一个论点
            证据：S1, S2

            ## 不写什么
            …""";

    static String outlineRevise(String feedback, Article a) {
        return "\n\n---\n用户对提纲的意见：\n" + feedback + "\n\n按意见改提纲，输出完整的新提纲。" + OUTLINE_FORMAT
                + requiredHint(a);
    }

    static String outlineReturn(List<Item> items, Article a) {
        StringBuilder sb = new StringBuilder("\n\n---\n评审认为提纲本身要改：\n");
        items.stream().filter(i -> "结构".equals(i.type()) || i.outline()).forEach(i -> sb.append("- ").append(i.text()).append("\n"));
        return sb.append("\n修订提纲（改完会再请用户确认），输出完整的新提纲。").append(OUTLINE_FORMAT)
                .append(requiredHint(a)).toString();
    }

    private static String requiredHint(Article a) {
        String genre = a.pack != null ? a.pack.name() : a.brief == null ? null : a.brief.genre();
        List<List<String>> req = a.pack != null ? a.pack.requiredSections() : REQUIRED.get(genre);
        if (req == null) {
            return "";
        }
        return "\n\n" + genre + "必须有这些小节：" + String.join("、", req.stream().map(o -> o.get(0)).toList()) + "。";
    }

    record Outline(List<String> titles, String thesis, List<Section> sections, String text, boolean parsed) {
    }

    static Outline parseOutline(String text) {
        List<String> titles = new ArrayList<>();
        List<Section> sections = new ArrayList<>();
        StringBuilder thesis = new StringBuilder();
        String part = "";
        String title = null, point = null;
        List<String> ids = new ArrayList<>();
        int budget = 0;
        for (String line : lines(text)) {
            String l = line.strip();
            Matcher sec = SECTION.matcher(l);
            if (sec.matches()) {
                if (title != null) {
                    sections.add(new Section(title, point, ids, budget));
                }
                title = sec.group(1).strip();
                budget = Integer.parseInt(sec.group(2));
                point = null;
                ids = new ArrayList<>();
                continue;
            }
            if (l.startsWith("## ") || l.equals("##")) {
                if (title != null) {
                    sections.add(new Section(title, point, ids, budget));
                    title = null;
                }
                part = l.contains("标题") ? "t" : l.contains("中心") ? "j" : l.contains("小节") ? "s" : "";
                continue;
            }
            if (part.equals("t")) {
                Matcher n = NUMBERED.matcher(l);
                Matcher r = ROW.matcher(l);
                if (n.matches()) {
                    titles.add(n.group(2).strip());
                } else if (r.matches()) {
                    titles.add(r.group(1).strip());
                }
            } else if (part.equals("j") && !l.isEmpty()) {
                thesis.append(thesis.isEmpty() ? "" : " ").append(l);
            } else if (part.equals("s") && title != null) {
                if (l.matches("^(?:[-*]\\s*)?论点\\s*[:：].*")) {
                    point = l.replaceFirst("^(?:[-*]\\s*)?论点\\s*[:：]\\s*", "");
                } else if (l.matches("^(?:[-*]\\s*)?证据\\s*[:：].*")) {
                    Matcher id = SID.matcher(l);
                    while (id.find()) {
                        ids.add(id.group());
                    }
                }
            }
        }
        if (title != null) {
            sections.add(new Section(title, point, ids, budget));
        }
        return new Outline(titles, thesis.isEmpty() ? null : thesis.toString(), sections, text == null ? "" : text.strip(),
                !sections.isEmpty() && !titles.isEmpty());
    }

    static List<String> outlineProblems(Outline o, Brief b, boolean hasSources, GenrePack pack) {
        List<String> out = outlineProblems(o, b, hasSources, pack == null ? REQUIRED.getOrDefault(b == null ? "" : b.genre(), List.of())
                : pack.requiredSections(), pack == null ? (b == null ? null : b.genre()) : pack.name());
        return out;
    }

    static List<String> outlineProblems(Outline o, Brief b, boolean hasSources) {
        return outlineProblems(o, b, hasSources, null);
    }

    private static List<String> outlineProblems(Outline o, Brief b, boolean hasSources, List<List<String>> required, String genre) {
        List<String> out = new ArrayList<>();
        int sum = o.sections().stream().mapToInt(Section::words).sum();
        if (b != null && b.words() > 0 && Math.abs(sum - b.words()) > b.words() * OUTLINE_BUDGET) {
            out.add("各节字数加起来是 " + sum + "，目标 " + b.words() + "（要在 ±" + Math.round(OUTLINE_BUDGET * 100) + "% 以内）");
        }
        if (hasSources) {
            o.sections().stream().filter(s -> s.sources().isEmpty())
                    .forEach(s -> out.add("「" + s.title() + "」的论点没有引用资料编号"));
        }
        if (o.thesis() == null) {
            out.add("没有写中心判断");
        }
        for (List<String> any : required) {
            if (o.sections().stream().noneMatch(s -> any.stream().anyMatch(s.title()::contains))) {
                out.add(genre + "缺少「" + any.get(0) + "」一节");
            }
        }
        return out;
    }

    static String outlineAsk(Article a, List<String> problems, boolean again) {
        StringBuilder sb = new StringBuilder(again ? "提纲按评审意见改过了，请再确认一次：\n\n" : "提纲写好了，请确认：\n\n");
        sb.append(a.outline).append("\n");
        if (!problems.isEmpty()) {
            sb.append("\n平台核对提纲时还有这些问题（可以照这样确认，也可以让它改）：\n");
            problems.forEach(p -> sb.append("- ").append(p).append("\n"));
        }
        return sb.append("\n👉 下一步：回复「确认提纲」，或直接写修改意见").toString();
    }

    // --- ④ draft ---

    static String draft(Article a, String title) {
        return "\n\n---\n提纲已经由用户确认。现在按提纲写全文：\n\n" + a.outline + "\n\n" + rules(a) + guide(a) + deliverRule(a, title);
    }

    static String section(Article a, int i) {
        Section s = a.sections.get(i);
        return "\n\n---\n" + (i == 0 ? "提纲已经由用户确认：\n\n" + a.outline + "\n\n" + rules(a) + guide(a) + "\n\n全文较长，一节一节写。" : "")
                + "现在写第 " + (i + 1) + "/" + a.sections.size() + " 节「" + s.title() + "」（约 " + s.words() + " 字，论点："
                + (s.point() == null ? "见提纲" : s.point()) + "）。只在回复里输出这一节的 Markdown，以「## " + s.title()
                + "」开头，接着前面写好的部分往下写；这一轮不要交付文件。";
    }

    static String assemble(Article a, String title) {
        return "\n\n---\n各节都写好了。现在统稿成全文：加上标题（一级标题，从备选标题里选一个），理顺各节之间的衔接，"
                + "不要改动各节的内容和长度。" + deliverRule(a, title);
    }

    static String redraft(Article a, List<Item> items, String title) {
        boolean outlineChanged = !a.reviews.isEmpty() && ReviewMerge.OUTLINE.equals(a.reviews.get(a.reviews.size() - 1).route());
        StringBuilder sb = new StringBuilder("\n\n---\n");
        sb.append(outlineChanged ? "提纲改过了，用户已重新确认：\n\n" + a.outline + "\n\n" : "资料补充过了，主张与证据已经更新。");
        if (!items.isEmpty()) {
            sb.append("这些问题要一起解决：\n");
            items.forEach(i -> sb.append("- ").append(i.text()).append("\n"));
        }
        return sb.append("\n只重写受影响的小节，其他部分保持不动。").append(rules(a)).append(guide(a)).append(deliverRule(a, title)).toString();
    }

    static final String WRITING_RULES = """
            写作要求：
            - 只用「主张与证据」里的事实；新加的事实、数字、引语必须注明资料编号（写成 [S1]）或链接；拿不准的明确写「不确定」。
            - 不留 TODO、（待补）、[图X] 这类占位符；不编造数据、人名、引语。
            - 小节标题和提纲一致，用 ## 二级标题；全文只有一个一级标题。
            - 照搬资料原文要加引号，并注明出处。""";

    /** The writing rules; an investigation's draft cites evidence entries and code places too. */
    static String rules(Article a) {
        return a.pack != null && a.pack.investigates() ? WRITING_RULES.replace("只用「主张与证据」里的事实；新加的事实、数字、引语必须注明资料编号（写成 [S1]）或链接；拿不准的明确写「不确定」。",
                "只用证据日志里的事实：关键判断和每个数字都要注明证据编号（写成 [E3]）；引用代码写成 `path:行号`（用反引号包住，行号要核对过）；"
                        + "图用 Mermaid 并编号；拿不准的明确写「不确定」「未验证」。") : WRITING_RULES;
    }

    private static String deliverRule(Article a, String title) {
        String tags = "[\"写作\", \"" + (a.brief == null || a.brief.genre() == null ? "长文" : a.brief.genre()) + "\"]";
        return "\n\n用 file_write 交付 Markdown 文件（文件名用「" + title + ".md」，tags 写 " + tags + "）"
                + (a.draftFileId == null ? "" : "，带 replace_id=" + a.draftFileId + " 覆盖原来的稿件，不要另存一份")
                + "。不要把全文贴在回复里，回复只写一两句交付说明。";
    }

    static String noDraft(Article a, String title) {
        return "\n\n---\n平台没有找到交付的 Markdown 稿件（或者是空的）。" + deliverRule(a, title);
    }

    // --- ⑥ review ---

    static final String EDITOR_PERSONA = "你是这篇稿件的编辑，独立评审（你看不到作者是怎么写的）。只根据下面给出的内容判断，不要调用工具，不要修改任何文件。";

    static String editor(Article a, String draft, List<Issue> checks, List<Response> declined) {
        return editor(a, draft, checks, declined, EDITOR_PERSONA);
    }

    static String editor(Article a, String draft, List<Issue> checks, List<Response> declined, String persona) {
        StringBuilder sb = new StringBuilder(persona + "\n" + """

                按 8 个维度逐项打 1～5 分，每项先引用原文作为依据，再给分和建议：
                1 结构 2 论点 3 证据 4 可读性 5 读者匹配 6 原创性 7 事实准确 8 语言规范
                另外看开头是否抓人、结尾有没有落点，并给 2～3 个备选标题。

                输出格式：
                维度 1 结构：分数 | 依据原句 | 建议
                …（8 行都要有）

                ## 问题
                - [严重|一般|建议][结构|论证|表达|规范] 问题描述 | 位置（小节标题或原句）
                （只有提纲本身要改——小节要增删或重排、中心判断要换——时，在这一条描述里写「需要改提纲」）

                ## 备选标题
                - …

                结论：通过 或 需修改

                """);
        sb.append("## 写作需求\n").append(brief(a.brief)).append("\n## 已确认的提纲\n").append(a.outline).append("\n\n## 平台核对\n");
        checks.stream().filter(i -> !ArticleChecks.PASS.equals(i.level()))
                .forEach(i -> sb.append("- ").append(i.failed() ? "失败" : "警告").append(" · ").append(i.check()).append("：")
                        .append(i.note()).append(i.where() == null ? "" : "（" + i.where() + "）").append("\n"));
        if (!declined.isEmpty()) {
            sb.append("\n## 作者上一轮不改的问题和理由（请判断理由是否成立，不成立就再列为问题）\n");
            declined.forEach(r -> sb.append("- ").append(r.id()).append("：").append(r.note()).append("\n"));
        }
        return sb.append("\n## 稿件\n").append(clip(draft, DRAFT_CHARS)).append("\n").toString();
    }

    static String facts(Article a, String draft, Map<String, String> sources, List<String> untraced) {
        StringBuilder sb = new StringBuilder("""
                你是事实核查员。从稿件里抽出可以核实的陈述（数字、日期、人名、引语、因果判断），以及下面列出的「待核实数字」，\
                逐条只对照下面给出的资料原文判断：成立 / 不成立 / 无法核实（资料里没有依据的都是无法核实，不要凭常识判断）。\
                「依据」必须原样摘抄资料里的一段话（平台会核对这段话是否真的在资料里）。不要调用工具，不要修改任何文件。

                每条一行：
                - 陈述 | 资料编号 | 成立 或 不成立 或 无法核实 | 依据（资料原文摘抄）

                """);
        sb.append("## 主张与证据\n");
        a.claims.forEach(c -> sb.append("- ").append(c.text()).append(" | ").append(String.join(",", c.sources())).append(" | ")
                .append(c.status()).append("\n"));
        if (!untraced.isEmpty()) {
            sb.append("\n## 待核实数字（平台在资料里没找到）\n").append(String.join("、", untraced)).append("\n");
        }
        sb.append("\n## 资料原文\n");
        int room = ALL_SOURCES_CHARS;
        if (sources.isEmpty()) {
            sb.append("（这个任务没有资料：所有陈述都只能判「无法核实」）\n");
        }
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String t = clip(e.getValue(), Math.max(500, Math.min(SOURCE_CHARS, room)));
            room -= t.length();
            sb.append("\n### ").append(e.getKey()).append("\n").append(t).append("\n");
        }
        return sb.append("\n## 稿件\n").append(clip(draft, DRAFT_CHARS)).append("\n").toString();
    }

    static final List<String> READER_QUESTIONS = List.of("读完前三段，还想不想读下去？为什么", "哪句看不懂（引用原句）",
            "在哪里失去了耐心（引用位置）", "哪里像空话、套话或在说教", "读完还缺什么、还有什么疑问", "哪个例子或数据最有说服力",
            "读完你能做什么、明白了什么", "会不会转给同事或朋友，为什么");

    static String reader(Article a, String draft) {
        Brief b = a.brief;
        StringBuilder sb = new StringBuilder("你是这篇文章的目标读者：").append(b == null ? "普通读者" : nz(b.audience()))
                .append(b == null || b.knows() == null ? "" : "；你已经知道：" + b.knows())
                .append("。按这个身份读下面的稿件，不要调用工具，不要修改任何文件。回答 8 个问题，每问一行：\n");
        for (int i = 0; i < READER_QUESTIONS.size(); i++) {
            sb.append(i + 1).append(". ").append(READER_QUESTIONS.get(i)).append("：…\n");
        }
        sb.append("""

                一句话复述：用一句话说出这篇文章的意思

                ## 问题
                - 读的时候遇到的问题（看不懂、没耐心、缺东西） | 位置（小节标题或原句）
                没有问题就写「- 无」。

                ## 稿件
                """);
        return sb.append(clip(draft, DRAFT_CHARS)).append("\n").toString();
    }

    static String consistent(String thesis, String retelling) {
        return "下面是一篇文章作者定的中心判断，和一位读者读完后的一句话复述。两者意思是否一致（允许措辞不同）？"
                + "只回答「一致」或「不一致」，后面加一句理由。不要调用工具。\n\n中心判断：" + thesis + "\n读者复述：" + retelling;
    }

    record Editor(List<Score> scores, List<Item> findings, List<String> titles, String verdict) {
    }

    static Editor parseEditor(String text) {
        List<Score> scores = new ArrayList<>();
        List<Item> findings = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        String verdict = null;
        String part = "";
        for (String line : lines(text)) {
            String l = line.strip();
            Matcher d = DIMENSION.matcher(l);
            if (d.matches()) {
                int dim = Integer.parseInt(d.group(1));
                if (dim >= 1 && dim <= 8 && scores.stream().noneMatch(s -> s.dimension() == dim)) {
                    scores.add(new Score(dim, ReviewMerge.DIMENSIONS.get(dim - 1), Integer.parseInt(d.group(3)),
                            d.group(4), d.group(5)));
                }
                continue;
            }
            if (l.startsWith("#")) {
                part = l.contains("标题") ? "t" : l.contains("问题") ? "f" : "";
                continue;
            }
            if (l.matches("^结论\\s*[:：].*")) {
                verdict = l.contains("需修改") || l.contains("不通过") ? "需修改" : "通过";
                continue;
            }
            Matcher f = FINDING.matcher(l);
            if (f.matches()) {
                String desc = f.group(3).strip();
                findings.add(new Item(null, f.group(1), f.group(2), desc, f.group(4) == null ? null : f.group(4).strip(), "编辑",
                        desc.contains("改提纲") || f.group(4) != null && f.group(4).contains("改提纲")));
                continue;
            }
            Matcher r = ROW.matcher(l);
            if (part.equals("t") && r.matches()) {
                titles.add(r.group(1).strip());
            }
        }
        scores.sort((x, y) -> Integer.compare(x.dimension(), y.dimension()));
        return new Editor(scores, findings, titles, verdict);
    }

    static List<Fact> parseFacts(String text) {
        List<Fact> out = new ArrayList<>();
        for (String line : lines(text)) {
            Matcher r = ROW.matcher(line.strip());
            if (!r.matches()) {
                continue;
            }
            String[] c = cells(r.group(1));
            if (c.length < 3) {
                continue;
            }
            String verdict = c[2].contains("不成立") ? "不成立" : c[2].contains("无法核实") ? "无法核实" : c[2].contains("成立") ? "成立" : null;
            if (verdict != null) {
                out.add(new Fact(c[0], c[1], verdict, at(c, 3), false));
            }
        }
        return out;
    }

    /**
     * The platform's spot check: a verdict stands only on evidence found word for word (ignoring spaces and
     * punctuation) in the sources; otherwise the statement becomes 无法核实.
     */
    static List<Fact> verify(List<Fact> facts, String pool) {
        String hay = ArticleChecks.squash(pool);
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) {
            if ("无法核实".equals(f.verdict())) {
                out.add(f);
                continue;
            }
            String ev = ArticleChecks.squash(f.evidence());
            boolean found = ev.length() >= 4 && hay.contains(ev);
            out.add(found ? f : new Fact(f.statement(), f.sources(), "无法核实",
                    (f.evidence() == null || f.evidence().isBlank() ? "（没有给依据）" : f.evidence()) + "（依据片段在资料里找不到）", true));
        }
        return out;
    }

    record Reader(List<String> answers, String retelling, List<Item> problems) {
    }

    static Reader parseReader(String text) {
        String[] answers = new String[READER_QUESTIONS.size()];
        String retelling = null;
        List<Item> problems = new ArrayList<>();
        boolean inProblems = false;
        for (String line : lines(text)) {
            String l = line.strip();
            if (l.startsWith("#")) {
                inProblems = l.contains("问题");
                continue;
            }
            if (l.matches("^一句话复述\\s*[:：].*")) {
                retelling = l.replaceFirst("^一句话复述\\s*[:：]\\s*", "");
                continue;
            }
            Matcher n = NUMBERED.matcher(l);
            if (!inProblems && n.matches()) {
                int i = Integer.parseInt(n.group(1)) - 1;
                if (i >= 0 && i < answers.length && answers[i] == null) {
                    answers[i] = n.group(2).replaceFirst("^[^：:]{0,30}[：:]\\s*", "").strip();
                }
                continue;
            }
            Matcher r = ROW.matcher(l);
            if (inProblems && r.matches()) {
                String[] c = cells(r.group(1));
                if (!c[0].isBlank() && !c[0].equals("无")) {
                    problems.add(new Item(null, "一般", "表达", c[0], at(c, 1).isEmpty() ? null : at(c, 1), "读者", false));
                }
            }
        }
        return new Reader(Arrays.stream(answers).map(x -> x == null ? "" : x).toList(), retelling, problems);
    }

    static Boolean parseConsistent(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.startsWith("不一致") || t.contains("不一致") ? Boolean.FALSE : t.contains("一致") ? Boolean.TRUE : null;
    }

    // --- ⑦ revise ---

    static String revise(Article a, List<Item> items, String hint, String title) {
        StringBuilder sb = new StringBuilder("\n\n---\n");
        if (hint != null) {
            sb.append("用户的意见（优先照这个改）：\n").append(hint).append("\n\n");
        }
        sb.append(switch (a.revisionFrom == null ? "" : a.revisionFrom) {
            case "check" -> "平台核对没通过，";
            case "review" -> "独立评审（编辑、事实核查、读者）没通过，";
            default -> "";
        }).append("下面是合并后的修改单：\n");
        items.forEach(i -> sb.append("- ").append(i.id()).append(" [").append(i.severity()).append("][").append(i.type()).append("] ")
                .append(i.text()).append(i.where() == null ? "" : "（位置：" + i.where() + "）").append("\n"));
        sb.append("""

                只改有问题的地方，不要重写全文，也不要为了整齐去统一各节长度（写作规范 skill 另有要求时照它）。\
                「建议」级的可以不改。""").append(deliverRule(a, title)).append("""


                回复里逐条写修改说明（严重和一般的每条都要有）：
                ## 修改说明
                - R1 已改 | 怎么改的
                - R2 不改 | 理由""");
        return sb.toString();
    }

    static String reviseMissing(List<Item> missing, boolean unchanged, Article a, String title) {
        StringBuilder sb = new StringBuilder("\n\n---\n");
        if (unchanged) {
            sb.append("平台发现稿件文件没有变化。");
        }
        if (!missing.isEmpty()) {
            sb.append("修改说明漏了这些：").append(String.join("、", missing.stream().map(Item::id).toList())).append("。");
        }
        return sb.append("把漏掉的处理完，交付改好的稿件").append(deliverRule(a, title))
                .append("\n\n回复里照「## 修改说明」的格式逐条写全（包括之前已经写过的）。").toString();
    }

    static List<Response> parseResponses(String text) {
        List<Response> out = new ArrayList<>();
        for (String line : lines(text)) {
            Matcher m = RESPONSE.matcher(line);
            if (m.matches() && out.stream().noneMatch(r -> r.id().equals(m.group(1)))) {
                out.add(new Response(m.group(1), m.group(2), m.group(3) == null ? "" : m.group(3).strip()));
            }
        }
        return out;
    }

    // --- stops ---

    static String checkStuck(List<Issue> fails, int rounds) {
        StringBuilder sb = new StringBuilder("平台核对修了 ").append(rounds).append(" 轮还有失败项：\n");
        fails.forEach(i -> sb.append("- ").append(i.check()).append("：").append(i.note())
                .append(i.where() == null ? "" : "（" + i.where() + "）").append("\n"));
        return sb.append("\n可以带着这些问题进入评审，也可以写修改意见再改一轮。\n\n👉 下一步：回复「继续交付」，或直接写修改意见").toString();
    }

    static String reviewStuck(List<Item> open, int total, int rounds) {
        StringBuilder sb = new StringBuilder("评审退回修改了 ").append(rounds).append(" 轮还没通过（编辑评分 ").append(total).append("/100）。剩下的问题：\n");
        open.stream().filter(Item::blocking).forEach(i -> sb.append("- [").append(i.severity()).append("] ").append(i.text()).append("\n"));
        return sb.append("\n可以照现在的样子交付，也可以写修改意见再改一轮。\n\n👉 下一步：回复「确认交付」，或直接写修改意见").toString();
    }

    // --- helpers ---

    static String brief(Brief b) {
        if (b == null) {
            return "（无）\n";
        }
        return "文体：" + nz(b.genre()) + "\n读者：" + nz(b.audience()) + "\n读者已知：" + nz(b.knows()) + "\n目的：" + nz(b.purpose())
                + "\n核心观点：" + nz(b.thesis()) + "\n字数：" + b.words() + "\n语气：" + nz(b.style()) + "\n必须用到的资料："
                + nz(b.mustUse()) + "\n不写什么：" + nz(b.exclude()) + "\n验收标准：" + nz(b.acceptance()) + "\n";
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "无" : s;
    }

    private static List<String> lines(String text) {
        return text == null ? List.of() : text.lines().toList();
    }

    private static String[] cells(String row) {
        return Arrays.stream(row.split("\\s*[|｜]\\s*")).map(String::strip).toArray(String[]::new);
    }

    private static String at(String[] c, int i) {
        return i < c.length ? c[i] : "";
    }

    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n…（后面还有内容，已截断）";
    }
}
