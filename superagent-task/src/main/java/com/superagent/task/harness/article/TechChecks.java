package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Evidence;
import com.superagent.task.harness.HarnessState.Article.Issue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.superagent.task.harness.article.ArticleChecks.FAIL;
import static com.superagent.task.harness.article.ArticleChecks.PASS;
import static com.superagent.task.harness.article.ArticleChecks.WARN;

/**
 * The platform checks a technical document gets on top of {@link ArticleChecks}, picked by the genre pack's
 * {@code checks}: code references, evidence ids, numbers against the evidence log, diagrams, required sections,
 * concreteness and banned phrases. Pure functions of the draft (and the repository or log it points at).
 * Fail = sends the draft back; warn = goes into the revision list only. Thresholds are first guesses, not calibrated.
 */
public final class TechChecks {

    static final double UNTRACED_FAIL = 0.20;
    static final double BANNED_FAIL_PER_1000 = 3;
    static final int BANNED_FAIL_MIN = 3;
    private static final String CODE_EXT = "java|js|ts|tsx|jsx|py|go|rs|kt|xml|yml|yaml|json|md|sql|sh|css|html|properties|toml|gradle|c|h|cpp|rb|php|swift|cs|vue";

    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"'，。、；）】]+");
    private static final Pattern REF = Pattern.compile("(?<![\\w/.\\-])((?:[\\w.\\-]+/)*[\\w\\-]+(?:\\.[\\w\\-]+)*\\.(?:" + CODE_EXT
            + ")):(\\d+)(?:[-–](\\d+))?(?:#([\\w.$<>]+))?");
    private static final Pattern CITE = Pattern.compile("[\\[［(（]\\s*(E\\d+(?:\\s*[,，、;；]\\s*E\\d+)*)\\s*[\\]］)）]");
    private static final Pattern EVIDENCE_ID = Pattern.compile("E\\d+");
    private static final Pattern FENCE = Pattern.compile("(?s)```.*?```");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");

    /** Phrases a reader takes as filler or as the voice of a machine; platform list, a pack may add to it. */
    static final List<String> BANNED = List.of("值得注意的是", "需要指出的是", "不难发现", "众所周知", "总而言之", "综上所述", "总的来说",
            "在当今", "随着时代的发展", "日新月异", "毋庸置疑", "不可否认", "让我们一起", "深入探讨", "至关重要", "扮演着重要的角色",
            "发挥着重要作用", "具有重要意义", "赋能", "抓手", "闭环", "颗粒度", "底层逻辑", "打通", "全方位", "多维度", "助力", "一站式",
            "无缝衔接", "织就", "画卷", "璀璨");
    private static final List<Pattern> BANNED_PATTERNS = List.of(Pattern.compile("不仅(?:仅)?[^，。；\\n]{0,24}[，,]?(?:更是|而且更|还是)"),
            Pattern.compile("不仅仅是[^，。；\\n]{0,24}[，,]?(?:更是|而是)"));
    /** Claims nobody can check unless they come with a number or something to compare to. */
    static final List<String> VAGUE = List.of("提升性能", "提高性能", "提升效率", "提高效率", "增强可扩展性", "提升可扩展性", "提高可扩展性",
            "优化体验", "提升体验", "降低成本", "显著提升", "大幅提升", "大幅降低", "显著降低", "极大提升", "增强稳定性", "提升稳定性",
            "增强可靠性", "提高可维护性", "提升可维护性", "增强灵活性");
    private static final Pattern CONCRETE = Pattern.compile("`[^`\\n]+`|(?<![\\w.])\\d{2,}|\\d+\\.\\d+|\\d+\\s*(?:ms|%|个|条|行|次|倍|秒|天|MB|GB|KB)"
            + "|[\\w.\\-]+/[\\w./\\-]+|\\bv\\d+\\b|\\[E\\d+\\]|\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b|\\b[a-z]+_[a-z_]+\\b");

    private TechChecks() {
    }

    // --- code references ---

    /** {@code symbol} may be null; {@code end} equals {@code start} for a single line. */
    record Ref(String path, int start, int end, String symbol, String text) {
    }

    static List<Ref> refs(String text) {
        List<Ref> out = new ArrayList<>();
        Matcher m = REF.matcher(URL.matcher(text == null ? "" : text).replaceAll(" "));
        while (m.find()) {
            int start = Integer.parseInt(m.group(2));
            out.add(new Ref(m.group(1), start, m.group(3) == null ? start : Integer.parseInt(m.group(3)), m.group(4), m.group()));
        }
        return out;
    }

    /** What is wrong with the reference (empty: it resolves): the file, the line range, the symbol. */
    static Optional<String> resolve(Path repo, Ref r) {
        return resolve(repo, r, new HashMap<>());
    }

    private static Optional<String> resolve(Path repo, Ref r, Map<Path, List<String>> cache) {
        Path root = repo.toAbsolutePath().normalize();
        Path file = root.resolve(r.path()).normalize();
        if (!file.startsWith(root)) {
            return Optional.of(r.text() + "：路径指到了仓库外面");
        }
        if (!Files.isRegularFile(file)) {
            return Optional.of(r.text() + "：文件不存在（路径要从仓库根算起）");
        }
        List<String> lines = cache.computeIfAbsent(file, f -> {
            try {
                return Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                try {
                    return new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).lines().toList();
                } catch (IOException io) {
                    return List.of();
                }
            }
        });
        if (r.start() < 1 || r.end() < r.start() || r.end() > lines.size()) {
            return Optional.of(r.text() + "：行号范围无效，文件只有 " + lines.size() + " 行");
        }
        if (r.symbol() != null && lines.subList(r.start() - 1, r.end()).stream().noneMatch(l -> l.contains(r.symbol()))) {
            return Optional.of(r.text() + "：第 " + r.start() + (r.end() > r.start() ? "-" + r.end() : "") + " 行里没有 " + r.symbol());
        }
        return Optional.empty();
    }

    /** The lines a reference points at, for a reviewer who can't open the repository. */
    static String snippet(Path repo, Ref r) {
        if (repo == null || resolve(repo, r).isPresent()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(repo.resolve(r.path()).normalize(), StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(r.start() - 1, Math.min(r.end(), r.start() + 39)));
        } catch (IOException e) {
            return null;
        }
    }

    /** Every {@code path:line} in the draft must be a real place in {@code repo}. */
    static List<Issue> codeRefs(String draft, Path repo) {
        List<Ref> refs = refs(FENCE.matcher(draft == null ? "" : draft).replaceAll(" "));
        if (refs.isEmpty()) {
            return List.of(new Issue("代码引用", PASS, null, "正文里没有 path:行号 形式的代码引用"));
        }
        if (repo == null) {
            return List.of(new Issue("代码引用", WARN, null, "有 " + refs.size() + " 处代码引用，但任务没有本地仓库，核对不了"));
        }
        Map<Path, List<String>> cache = new HashMap<>();
        List<Issue> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Ref r : refs) {
            if (seen.add(r.text())) {
                resolve(repo, r, cache).ifPresent(err -> out.add(new Issue("代码引用", FAIL, r.text(), err)));
            }
        }
        return out.isEmpty() ? List.of(new Issue("代码引用", PASS, null, seen.size() + " 处代码引用都能在仓库里找到")) : out;
    }

    // --- evidence ids ---

    /** Cited ids must exist (fail); with a log, a draft that cites none fails, and an entry nobody cites warns. */
    static List<Issue> evidenceIds(String draft, List<Evidence> log) {
        Set<String> cited = new LinkedHashSet<>();
        Matcher m = CITE.matcher(draft == null ? "" : draft);
        while (m.find()) {
            Matcher id = EVIDENCE_ID.matcher(m.group(1));
            while (id.find()) {
                cited.add(id.group());
            }
        }
        Set<String> known = new LinkedHashSet<>();
        log.forEach(e -> known.add(e.id()));
        List<Issue> out = new ArrayList<>();
        cited.stream().filter(c -> !known.contains(c)).forEach(c -> out.add(new Issue("证据编号", FAIL, c, "证据日志里没有 " + c)));
        if (!known.isEmpty() && cited.isEmpty()) {
            out.add(new Issue("证据编号", FAIL, null, "正文没有引用任何证据编号（关键判断要写成 [E1]）"));
        }
        known.stream().filter(k -> !cited.contains(k)).forEach(k -> out.add(new Issue("证据编号", WARN, k, "证据 " + k + " 一次都没被引用")));
        return out.isEmpty() ? List.of(new Issue("证据编号", PASS, null, "引用 " + cited.size() + " 条证据，编号都存在")) : out;
    }

    // --- numbers ---

    /** Numbers in the prose must be in the evidence log: more than a fifth missing fails, any missing warns. */
    static List<Issue> numbers(ArticleChecks.Doc doc, String pool, List<String> untraced) {
        ArticleChecks.Doc bare = ArticleChecks.Doc.of(CITE.matcher(REF.matcher(doc.text).replaceAll(" ")).replaceAll(" "));
        List<Issue> r = ArticleChecks.numbers(bare, pool, untraced);
        if (r.isEmpty()) {
            return List.of(new Issue("数字溯源", WARN, null, "没有证据日志，数字无处可查"));
        }
        Issue i = r.get(0);
        if (untraced.isEmpty()) {
            return r;
        }
        long total = Long.parseLong(i.note().replaceFirst("^(\\d+) 个数字.*", "$1"));
        boolean fail = untraced.size() / (double) total > UNTRACED_FAIL && untraced.size() >= 2;
        return List.of(new Issue("数字溯源", fail ? FAIL : WARN, null, i.note()));
    }

    // --- required sections ---

    static List<Issue> requiredSections(ArticleChecks.Doc doc, GenrePack pack) {
        List<Issue> out = new ArrayList<>();
        for (List<String> any : pack.requiredSections()) {
            boolean found = doc.headings.stream().filter(h -> h.level() >= 2)
                    .anyMatch(h -> any.stream().anyMatch(a -> ArticleChecks.normTitle(h.text()).contains(ArticleChecks.normTitle(a))));
            if (!found) {
                out.add(new Issue("必备小节", FAIL, any.get(0), pack.name() + "缺少「" + any.get(0) + "」一节"
                        + (any.size() > 1 ? "（标题里要有：" + String.join("、", any) + " 之一）" : "")));
            }
        }
        return out.isEmpty() ? List.of(new Issue("必备小节", PASS, null, pack.requiredSections().size() + " 个必备小节都在")) : out;
    }

    // --- concreteness ---

    /** Each top-level section needs something checkable; a vague claim without a number in its sentence fails. */
    static List<Issue> concreteness(String draft) {
        List<Issue> out = new ArrayList<>();
        for (Map.Entry<String, String> s : sections(draft).entrySet()) {
            if (!CONCRETE.matcher(s.getValue()).find()) {
                out.add(new Issue("具体度", FAIL, s.getKey(), "这一节没有任何可核对的具体内容（数字、标识符、路径、版本、命令）"));
            }
        }
        String prose = FENCE.matcher(draft == null ? "" : draft).replaceAll("");
        for (String sentence : SENTENCE_END.split(prose)) {
            for (String v : VAGUE) {
                if (sentence.contains(v) && !sentence.matches("(?s).*\\d.*")) {
                    out.add(new Issue("具体度", FAIL, ArticleChecks.clip(sentence.strip()), "「" + v + "」没有数字或比较对象，读者没法核对"));
                    break;
                }
            }
        }
        return out.isEmpty() ? List.of(new Issue("具体度", PASS, null, "每一节都有可核对的内容，没有无数字的空话")) : out;
    }

    /** Top-level sections by title with their text (code blocks kept: a command is concrete). */
    static Map<String, String> sections(String draft) {
        List<String[]> heads = new ArrayList<>(); // level, title
        List<StringBuilder> bodies = new ArrayList<>();
        boolean fenced = false;
        Pattern h = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
        List<int[]> at = new ArrayList<>();
        List<String> lines = (draft == null ? "" : draft).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.stripLeading().startsWith("```")) {
                fenced = !fenced;
            }
            Matcher m = h.matcher(l);
            if (!fenced && m.matches()) {
                heads.add(new String[]{String.valueOf(m.group(1).length()), m.group(2).strip()});
                at.add(new int[]{i});
            }
        }
        long h1 = heads.stream().filter(x -> x[0].equals("1")).count();
        int top = heads.stream().filter(x -> h1 != 1 || !x[0].equals("1")).mapToInt(x -> Integer.parseInt(x[0])).min().orElse(2);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < heads.size(); i++) {
            if (Integer.parseInt(heads.get(i)[0]) != top) {
                continue;
            }
            int from = at.get(i)[0] + 1, to = lines.size();
            for (int j = i + 1; j < heads.size(); j++) {
                if (Integer.parseInt(heads.get(j)[0]) <= top) {
                    to = at.get(j)[0];
                    break;
                }
            }
            out.put(heads.get(i)[1], String.join("\n", lines.subList(from, to)));
        }
        return out;
    }

    // --- banned phrases ---

    /** Hits with the sentence they sit in; three or more at three per thousand characters fail, any hit warns. */
    static List<Issue> bannedPhrases(ArticleChecks.Doc doc, List<String> extra) {
        List<String> words = new ArrayList<>(BANNED);
        words.addAll(extra);
        List<Issue> hits = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(doc.prose())) {
            String hit = words.stream().filter(sentence::contains).findFirst()
                    .orElseGet(() -> BANNED_PATTERNS.stream().map(p -> p.matcher(sentence)).filter(Matcher::find).map(Matcher::group).findFirst().orElse(null));
            if (hit != null) {
                hits.add(new Issue("禁用语", WARN, ArticleChecks.clip(sentence.strip()), "出现「" + hit + "」：删掉，直接写那件事"));
            }
        }
        int chars = Math.max(1, ArticleChecks.count(doc.prose()));
        double per1000 = hits.size() * 1000.0 / chars;
        if (hits.size() >= BANNED_FAIL_MIN && per1000 >= BANNED_FAIL_PER_1000) {
            return hits.stream().map(i -> new Issue(i.check(), FAIL, i.where(), i.note() + "（全文 " + hits.size() + " 处，密度太高）")).toList();
        }
        return hits.isEmpty() ? List.of(new Issue("禁用语", PASS, null, "没有禁用语")) : hits;
    }
}
