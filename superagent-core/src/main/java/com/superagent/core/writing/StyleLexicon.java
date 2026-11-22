package com.superagent.core.writing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The banned words, sentence patterns and vague claims the plain-writing standard lists, read from a fenced
 * {@code lexicon} block in that skill: sections {@code [banned]}, {@code [patterns]} (regular expressions) and
 * {@code [vague]}, one entry per line, {@code #} starts a comment.
 */
public record StyleLexicon(List<String> banned, List<String> patterns, List<String> vague) {

    public static final String SKILL_NAME = "去 AI 味写作规范";
    private static final Pattern BLOCK = Pattern.compile("(?s)```lexicon[ \\t]*\\R(.*?)\\R?```");

    /** Used when the company has no such skill or it has no block: small, so checks still work. */
    public static final StyleLexicon FALLBACK = new StyleLexicon(
            List.of("值得注意的是", "需要指出的是", "不难发现", "众所周知", "总而言之", "综上所述", "总的来说", "首先，", "其次，", "最后，",
                    "在当今", "随着时代的发展", "日新月异", "毋庸置疑", "不可否认", "让我们一起", "深入探讨", "至关重要",
                    "扮演着重要的角色", "发挥着重要作用", "具有重要意义", "一方面", "另一方面", "赋能", "抓手", "闭环", "颗粒度",
                    "底层逻辑", "打通", "全方位", "多维度", "助力", "一站式", "无缝衔接", "织就", "画卷", "璀璨"),
            List.of("不仅(?:仅)?[^，。；\\n]{0,24}[，,]?(?:更是|而且更|还是)", "不仅仅是[^，。；\\n]{0,24}[，,]?(?:更是|而是)"),
            List.of("提升性能", "提高性能", "提升效率", "提高效率", "增强可扩展性", "提升可扩展性", "提高可扩展性", "优化体验", "提升体验",
                    "降低成本", "显著提升", "大幅提升", "大幅降低", "显著降低", "极大提升", "增强稳定性", "提升稳定性", "增强可靠性",
                    "提高可维护性", "提升可维护性", "增强灵活性"));

    public StyleLexicon {
        banned = List.copyOf(banned);
        patterns = List.copyOf(patterns);
        vague = List.copyOf(vague);
    }

    public static boolean hasBlock(String content) {
        return content != null && BLOCK.matcher(content).find();
    }

    /** Null when the skill has no block; throws {@link StyleLexiconException} naming the skill when the block is wrong. */
    public static StyleLexicon parse(String skillName, String content) {
        java.util.regex.Matcher m = BLOCK.matcher(content == null ? "" : content);
        if (!m.find()) {
            return null;
        }
        List<String> banned = new ArrayList<>(), patterns = new ArrayList<>(), vague = new ArrayList<>();
        List<String> into = null;
        for (String raw : m.group(1).lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                into = switch (line.substring(1, line.length() - 1).strip()) {
                    case "banned" -> banned;
                    case "patterns" -> patterns;
                    case "vague" -> vague;
                    default -> throw new StyleLexiconException(skillName, "未知小节 " + line + "（只能是 [banned]、[patterns]、[vague]）");
                };
            } else if (into == null) {
                throw new StyleLexiconException(skillName, "词条「" + line + "」前面没有小节标题");
            } else {
                if (into == patterns) {
                    try {
                        Pattern.compile(line);
                    } catch (PatternSyntaxException e) {
                        throw new StyleLexiconException(skillName, "句式「" + line + "」不是有效的正则：" + e.getDescription());
                    }
                }
                into.add(line);
            }
        }
        if (banned.isEmpty() && patterns.isEmpty() && vague.isEmpty()) {
            throw new StyleLexiconException(skillName, "词表块是空的");
        }
        return new StyleLexicon(banned, patterns, vague);
    }

    /** The skill {@code selector} names (id or name; blank = the plain-writing standard) among {@code skills}, null when none. */
    public static com.superagent.core.skill.Skill pick(List<com.superagent.core.skill.Skill> skills, String selector) {
        String want = selector == null || selector.isBlank() ? SKILL_NAME : selector.strip();
        return skills.stream().filter(s -> s.id().equals(want)).findFirst()
                .or(() -> skills.stream().filter(s -> s.name().strip().equalsIgnoreCase(want)).findFirst()).orElse(null);
    }

    /** The word list of the picked skill; {@link #FALLBACK} when there is no such skill or it has no block. */
    public static StyleLexicon of(List<com.superagent.core.skill.Skill> skills, String selector) {
        com.superagent.core.skill.Skill s = pick(skills, selector);
        StyleLexicon lex = s == null ? null : parse(s.name(), s.content());
        return lex == null ? FALLBACK : lex;
    }

    public StyleLexicon withExtraBanned(List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        LinkedHashSet<String> all = new LinkedHashSet<>(banned);
        all.addAll(extra);
        return new StyleLexicon(new ArrayList<>(all), patterns, vague);
    }

    public static class StyleLexiconException extends IllegalArgumentException {
        public StyleLexiconException(String skill, String why) {
            super("skill「" + skill + "」的词表有误：" + why);
        }
    }
}
