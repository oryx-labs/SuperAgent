package com.superagent.task.harness.article;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the writing flow reads from a genre pack skill: the configuration block at the top of its text (YAML between
 * two {@code ---} lines). The rest of the text is for the models. A pack with a wrong block fails loudly when it is
 * loaded, naming the skill and the field.
 *
 * <pre>
 * genre: tech-design                    # required: the genre's id
 * research: collect | investigate       # default collect; investigate keeps an evidence log from reading the repo
 * needs_repo: true | false              # default false; true: the task must name a local repository
 * word_range: [3000, 20000]             # default [500, 30000]
 * required_sections:                    # required, at least one; each entry is a title or a list of accepted titles
 *   - [背景]
 *   - [备选方案, 备选]
 * checks: [code_refs, evidence_ids, numbers, mermaid, concreteness, banned_phrases, links, wechat]
 * reviewers: [architect, verifier, implementer]   # one per slot: editor|architect, fact_checker|verifier, reader|implementer
 * weights: 技术文章                      # the editor's weights: 公众号 | 报告 | 技术文章 | 讲义 (default 报告)
 * max_open_questions: 5                 # more open questions from the implementer than this fails the review
 * min_sources: 1                        # readable materials needed when any were given (collect)
 * standards: [技术画图规范]               # other skills whose text goes to the author with this one
 * banned_phrases: [赋能]                 # added to the platform's list
 * </pre>
 */
public record GenrePack(String name, String genre, String research, boolean needsRepo, int minWords, int maxWords,
                        List<List<String>> requiredSections, List<String> checks, List<String> reviewers, String weights,
                        int maxOpenQuestions, int minSources, List<String> standards, List<String> bannedPhrases) {

    public static final String COLLECT = "collect", INVESTIGATE = "investigate";
    public static final String CODE_REFS = "code_refs", EVIDENCE_IDS = "evidence_ids", NUMBERS = "numbers", MERMAID = "mermaid",
            CONCRETENESS = "concreteness", BANNED = "banned_phrases", LINKS = "links", WECHAT = "wechat";
    public static final Set<String> CHECKS = Set.of(CODE_REFS, EVIDENCE_IDS, NUMBERS, MERMAID, CONCRETENESS, BANNED, LINKS, WECHAT);
    public static final String EDITOR = "editor", ARCHITECT = "architect", FACT_CHECKER = "fact_checker", VERIFIER = "verifier",
            READER = "reader", IMPLEMENTER = "implementer";
    static final Set<String> SCORERS = Set.of(EDITOR, ARCHITECT), CHECKERS = Set.of(FACT_CHECKER, VERIFIER), READERS = Set.of(READER, IMPLEMENTER);
    static final Set<String> WEIGHTS = Set.of("公众号", "报告", "技术文章", "讲义");
    private static final Set<String> KEYS = Set.of("genre", "research", "needs_repo", "word_range", "required_sections", "checks",
            "reviewers", "weights", "max_open_questions", "min_sources", "standards", "banned_phrases");
    private static final Pattern FRONT = Pattern.compile("\\A\\s*---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);

    public boolean investigates() {
        return INVESTIGATE.equals(research);
    }

    public boolean has(String check) {
        return checks.contains(check);
    }

    public boolean hasReviewer(String reviewer) {
        return reviewers.contains(reviewer);
    }

    /** Whether a skill's text is a genre pack: it opens with a configuration block that has a {@code genre} key. */
    public static boolean isPack(String content) {
        Matcher m = FRONT.matcher(content == null ? "" : content);
        return m.find() && Pattern.compile("(?m)^genre\\s*:").matcher(m.group(1)).find();
    }

    /** The skill's text without its configuration block. */
    public static String body(String content) {
        String c = content == null ? "" : content;
        Matcher m = FRONT.matcher(c);
        return (m.find() ? c.substring(m.end()) : c).strip();
    }

    public static GenrePack parse(String name, String content) {
        Matcher m = FRONT.matcher(content == null ? "" : content);
        if (!m.find()) {
            throw new GenrePackException(name, null, "正文开头没有用 --- 包起来的配置块");
        }
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(10);
            root = new Yaml(new SafeConstructor(options)).load(m.group(1));
        } catch (RuntimeException e) {
            throw new GenrePackException(name, null, "配置块不是合法的 YAML（" + firstLine(e.getMessage()) + "）");
        }
        if (!(root instanceof Map<?, ?> raw)) {
            throw new GenrePackException(name, null, "配置块应该是「字段: 值」的列表");
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        raw.forEach((k, v) -> cfg.put(String.valueOf(k), v));
        cfg.keySet().stream().filter(k -> !KEYS.contains(k)).findFirst().ifPresent(k -> {
            throw new GenrePackException(name, k, "未知字段，可用的有：" + String.join("、", KEYS.stream().sorted().toList()));
        });

        String genre = string(name, cfg, "genre", null);
        if (genre == null || genre.isBlank() || genre.length() > 40) {
            throw new GenrePackException(name, "genre", "必填，是这个文体的标识（40 字以内）");
        }
        String research = string(name, cfg, "research", COLLECT);
        if (!research.equals(COLLECT) && !research.equals(INVESTIGATE)) {
            throw new GenrePackException(name, "research", "只能是 collect 或 investigate，现在是「" + research + "」");
        }
        boolean needsRepo = bool(name, cfg, "needs_repo");
        int[] range = wordRange(name, cfg.get("word_range"));
        List<List<String>> sections = sections(name, cfg.get("required_sections"));
        List<String> checks = names(name, cfg, "checks", CHECKS, "检查项");
        if (checks.contains(EVIDENCE_IDS) && !research.equals(INVESTIGATE)) {
            throw new GenrePackException(name, "checks", "evidence_ids 要有证据日志，需要 research: investigate");
        }
        List<String> reviewers = cfg.containsKey("reviewers") ? names(name, cfg, "reviewers",
                Set.of(EDITOR, ARCHITECT, FACT_CHECKER, VERIFIER, READER, IMPLEMENTER), "评审角色")
                : List.of(EDITOR, FACT_CHECKER, READER);
        slots(name, reviewers);
        String weights = string(name, cfg, "weights", "报告");
        if (!WEIGHTS.contains(weights)) {
            throw new GenrePackException(name, "weights", "只能是 " + String.join("、", WEIGHTS.stream().sorted().toList()) + " 之一");
        }
        int open = integer(name, cfg, "max_open_questions", 5);
        int minSources = integer(name, cfg, "min_sources", 1);
        return new GenrePack(name, genre.strip(), research, needsRepo, range[0], range[1], sections, checks, reviewers, weights,
                open, minSources, strings(name, cfg, "standards"), strings(name, cfg, "banned_phrases"));
    }

    private static void slots(String name, List<String> reviewers) {
        for (Set<String> slot : List.of(SCORERS, CHECKERS, READERS)) {
            if (reviewers.stream().filter(slot::contains).count() > 1) {
                throw new GenrePackException(name, "reviewers", "「" + String.join("、", slot.stream().sorted().toList())
                        + "」是同一个位置的角色，只能选一个");
            }
        }
        if (reviewers.stream().noneMatch(SCORERS::contains)) {
            throw new GenrePackException(name, "reviewers", "至少要有 editor 或 architect（负责打分的那一位）");
        }
    }

    private static int[] wordRange(String name, Object v) {
        if (v == null) {
            return new int[]{500, 30000};
        }
        if (v instanceof List<?> l && l.size() == 2 && l.get(0) instanceof Integer lo && l.get(1) instanceof Integer hi) {
            if (lo <= 0 || lo > hi) {
                throw new GenrePackException(name, "word_range", "要写成 [最少, 最多]，且 0 < 最少 ≤ 最多，现在是 [" + lo + ", " + hi + "]");
            }
            return new int[]{lo, hi};
        }
        throw new GenrePackException(name, "word_range", "要写成两个整数的列表，如 [3000, 20000]");
    }

    private static List<List<String>> sections(String name, Object v) {
        if (!(v instanceof List<?> l) || l.isEmpty()) {
            throw new GenrePackException(name, "required_sections", "必填，至少列一个必备小节");
        }
        List<List<String>> out = new ArrayList<>();
        for (Object o : l) {
            List<String> any = new ArrayList<>();
            if (o instanceof String s && !s.isBlank()) {
                any.add(s.strip());
            } else if (o instanceof List<?> alts && !alts.isEmpty()) {
                for (Object a : alts) {
                    if (!(a instanceof String s) || s.isBlank()) {
                        throw new GenrePackException(name, "required_sections", "小节标题不能为空：" + o);
                    }
                    any.add(s.strip());
                }
            } else {
                throw new GenrePackException(name, "required_sections", "每一项要么是小节标题，要么是可接受的几个标题的列表：" + o);
            }
            out.add(List.copyOf(any));
        }
        return List.copyOf(out);
    }

    private static List<String> names(String pack, Map<String, Object> cfg, String field, Set<String> allowed, String what) {
        List<String> out = strings(pack, cfg, field);
        for (String s : out) {
            if (!allowed.contains(s)) {
                throw new GenrePackException(pack, field, "未知" + what + "「" + s + "」，可用的有：" + String.join("、", allowed.stream().sorted().toList()));
            }
        }
        return out;
    }

    private static List<String> strings(String pack, Map<String, Object> cfg, String field) {
        Object v = cfg.get(field);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new GenrePackException(pack, field, "要写成列表，如 [a, b]");
        }
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof String s) || s.isBlank()) {
                throw new GenrePackException(pack, field, "列表里的每一项要是非空文字：" + o);
            }
            out.add(s.strip());
        }
        return List.copyOf(out);
    }

    private static String string(String pack, Map<String, Object> cfg, String field, String dflt) {
        Object v = cfg.get(field);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof String s)) {
            throw new GenrePackException(pack, field, "要写成文字，现在是 " + v);
        }
        return s.strip();
    }

    private static boolean bool(String pack, Map<String, Object> cfg, String field) {
        Object v = cfg.get(field);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new GenrePackException(pack, field, "要写 true 或 false，现在是 " + v);
    }

    private static int integer(String pack, Map<String, Object> cfg, String field, int dflt) {
        Object v = cfg.get(field);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Integer i && i >= 0) {
            return i;
        }
        throw new GenrePackException(pack, field, "要写成不小于 0 的整数，现在是 " + v);
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("").strip();
    }
}
