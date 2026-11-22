package com.superagent.core.writing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic checks on prose shared by every flow: banned words, concreteness, heading structure. */
public final class TextChecks {

    public static final String PASS = "pass", WARN = "warn", FAIL = "fail";
    public static final double FILLER_WARN = 3, DASH_WARN = 3, BANNED_FAIL_PER_1000 = 3;
    public static final int BANNED_FAIL_MIN = 3;

    public record Finding(String check, String level, String where, String note) {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("(?s)```.*?```");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");
    private static final Pattern CONCRETE = Pattern.compile("`[^`\\n]+`|(?<![\\w.])\\d{2,}|\\d+\\.\\d+|\\d+\\s*(?:ms|%|个|条|行|次|倍|秒|天|MB|GB|KB)"
            + "|[\\w.\\-]+/[\\w./\\-]+|\\bv\\d+\\b|\\[E\\d+\\]|\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b|\\b[a-z]+_[a-z_]+\\b");

    private TextChecks() {
    }

    /** All three checks as warnings only: what a general task's or a chat file's Markdown gets. */
    public static List<Finding> warnings(String markdown, StyleLexicon lex) {
        String prose = prose(markdown);
        List<Finding> out = new ArrayList<>();
        out.addAll(bannedDensity(prose, lex, List.of(), false));
        out.addAll(concreteness(markdown, lex, WARN));
        out.addAll(headings(markdown, WARN));
        return out.stream().filter(f -> !PASS.equals(f.level())).toList();
    }

    // --- banned words ---

    /** Hits with the sentence they sit in: any hit warns; with {@code failWhenDense}, 3+ at 3 per thousand characters fail. */
    public static List<Finding> bannedPhrases(String prose, StyleLexicon lex, List<String> extra, boolean failWhenDense) {
        List<String> words = new ArrayList<>(lex.banned());
        words.addAll(extra);
        List<Pattern> patterns = lex.patterns().stream().map(Pattern::compile).toList();
        List<Finding> hits = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(prose)) {
            String hit = words.stream().filter(sentence::contains).findFirst()
                    .orElseGet(() -> patterns.stream().map(p -> p.matcher(sentence)).filter(Matcher::find).map(Matcher::group).findFirst().orElse(null));
            if (hit != null) {
                hits.add(new Finding("禁用语", WARN, clip(sentence.strip()), "出现「" + hit + "」：删掉，直接写那件事"));
            }
        }
        int chars = Math.max(1, count(prose));
        double per1000 = hits.size() * 1000.0 / chars;
        if (failWhenDense && hits.size() >= BANNED_FAIL_MIN && per1000 >= BANNED_FAIL_PER_1000) {
            return hits.stream().map(i -> new Finding(i.check(), FAIL, i.where(), i.note() + "（全文 " + hits.size() + " 处，密度太高）")).toList();
        }
        return hits.isEmpty() ? List.of(new Finding("禁用语", PASS, null, "没有禁用语")) : hits;
    }

    /** The general path: density per thousand characters decides, one warning for the whole text. */
    static List<Finding> bannedDensity(String prose, StyleLexicon lex, List<String> extra, boolean unused) {
        int chars = Math.max(1, squash(prose).length());
        Map<String, Integer> hits = new LinkedHashMap<>();
        List<String> words = new ArrayList<>(lex.banned());
        words.addAll(extra);
        for (String w : words) {
            int n = occurrences(prose, w);
            if (n > 0) {
                hits.put(w.replace("，", ""), n);
            }
        }
        for (String p : lex.patterns()) {
            Matcher m = Pattern.compile(p).matcher(prose);
            while (m.find()) {
                hits.merge(m.group(), 1, Integer::sum);
            }
        }
        return density(prose, chars, hits);
    }

    /** Stock phrases per thousand characters and dashes: a hint, never a fail. */
    public static List<Finding> filler(String prose, StyleLexicon lex) {
        return bannedDensity(prose, lex, List.of(), false);
    }

    private static List<Finding> density(String prose, int chars, Map<String, Integer> hits) {
        int total = hits.values().stream().mapToInt(Integer::intValue).sum();
        double perK = total * 1000.0 / chars;
        double dashes = occurrences(prose, "——") * 1000.0 / chars;
        List<Finding> out = new ArrayList<>();
        if (perK > FILLER_WARN) {
            out.add(new Finding("套话和 AI 腔", WARN, null, String.format(Locale.ROOT, "每千字 %.1f 次：", perK)
                    + String.join("、", hits.entrySet().stream().map(e -> e.getKey() + "×" + e.getValue()).toList())));
        }
        if (dashes > DASH_WARN) {
            out.add(new Finding("套话和 AI 腔", WARN, null, String.format(Locale.ROOT, "破折号每千字 %.1f 个", dashes)));
        }
        return out.isEmpty() ? List.of(new Finding("套话和 AI 腔", PASS, null, "套话不多")) : out;
    }

    // --- concreteness ---

    /** Each top-level section needs something checkable; a vague claim without a number in its sentence is flagged. */
    public static List<Finding> concreteness(String draft, StyleLexicon lex, String level) {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, String> s : sections(draft).entrySet()) {
            if (!CONCRETE.matcher(s.getValue()).find()) {
                out.add(new Finding("具体度", level, s.getKey(), "这一节没有任何可核对的具体内容（数字、标识符、路径、版本、命令）"));
            }
        }
        String prose = FENCE.matcher(draft == null ? "" : draft).replaceAll("");
        for (String sentence : SENTENCE_END.split(prose)) {
            for (String v : lex.vague()) {
                if (sentence.contains(v) && !sentence.matches("(?s).*\\d.*")) {
                    out.add(new Finding("具体度", level, clip(sentence.strip()), "「" + v + "」没有数字或比较对象，读者没法核对"));
                    break;
                }
            }
        }
        return out.isEmpty() ? List.of(new Finding("具体度", PASS, null, "每一节都有可核对的内容，没有无数字的空话")) : out;
    }

    /** Top-level sections by title with their text (code blocks kept: a command is concrete). */
    public static Map<String, String> sections(String draft) {
        List<String[]> heads = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        boolean fenced = false;
        List<String> lines = (draft == null ? "" : draft).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.stripLeading().startsWith("```")) {
                fenced = !fenced;
            }
            Matcher m = HEADING.matcher(l);
            if (!fenced && m.matches()) {
                heads.add(new String[]{String.valueOf(m.group(1).length()), m.group(2).strip()});
                at.add(i);
            }
        }
        long h1 = heads.stream().filter(x -> x[0].equals("1")).count();
        int top = heads.stream().filter(x -> h1 != 1 || !x[0].equals("1")).mapToInt(x -> Integer.parseInt(x[0])).min().orElse(2);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < heads.size(); i++) {
            if (Integer.parseInt(heads.get(i)[0]) != top) {
                continue;
            }
            int from = at.get(i) + 1, to = lines.size();
            for (int j = i + 1; j < heads.size(); j++) {
                if (Integer.parseInt(heads.get(j)[0]) <= top) {
                    to = at.get(j);
                    break;
                }
            }
            out.put(heads.get(i)[1], String.join("\n", lines.subList(from, to)));
        }
        return out;
    }

    // --- headings ---

    /** One H1 at most, and no jump of more than one level. */
    public static List<Finding> headings(String text, String level) {
        String body = FENCE.matcher(text == null ? "" : text).replaceAll("");
        List<int[]> hs = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (String l : body.lines().toList()) {
            Matcher h = HEADING.matcher(l);
            if (h.matches()) {
                hs.add(new int[]{h.group(1).length()});
                titles.add(h.group(2).strip());
            }
        }
        List<Finding> out = new ArrayList<>();
        long h1 = hs.stream().filter(h -> h[0] == 1).count();
        if (h1 > 1) {
            out.add(new Finding("标题层级", level, null, "一级标题有 " + h1 + " 个，最多一个"));
        }
        int prev = 0;
        for (int i = 0; i < hs.size(); i++) {
            int lv = hs.get(i)[0];
            if (prev > 0 && lv > prev + 1) {
                out.add(new Finding("标题层级", level, titles.get(i), "从 " + "#".repeat(prev) + " 跳到了 " + "#".repeat(lv)));
            }
            prev = lv;
        }
        return out.isEmpty() ? List.of(new Finding("标题层级", PASS, null, "层级连续")) : out;
    }

    // --- helpers ---

    /** Lines of the text outside code blocks that are neither blank nor headings. */
    public static String prose(String text) {
        return FENCE.matcher(text == null ? "" : text).replaceAll("").lines()
                .filter(l -> !l.isBlank() && !HEADING.matcher(l).matches()).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    /** Han characters and Latin words, the way Chinese writing is counted. */
    public static int count(String text) {
        int n = 0;
        boolean word = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                n++;
                word = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!word) {
                    n++;
                }
                word = true;
            } else {
                word = false;
            }
        }
        return n;
    }

    public static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\p{P}\\p{S}]", "").toLowerCase(Locale.ROOT);
    }

    static int occurrences(String text, String w) {
        int n = 0;
        for (int i = text.indexOf(w); i >= 0; i = text.indexOf(w, i + w.length())) {
            n++;
        }
        return n;
    }

    public static String clip(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}
