package com.superagent.task.harness.article;

import com.superagent.core.writing.StyleLexicon;
import com.superagent.core.writing.TextChecks;
import com.superagent.task.harness.HarnessState.Article.Brief;
import com.superagent.task.harness.HarnessState.Article.Issue;
import com.superagent.task.harness.HarnessState.Article.Section;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The writing flow's platform checks on a draft: deterministic, no model involved, so a result can be reproduced.
 * Each finding is a fail (sends the draft back) or a warning (goes into the revision list, never blocks). The
 * thresholds are first guesses kept here as constants; they are not calibrated yet.
 */
public final class ArticleChecks {

    static final String PASS = "pass", WARN = "warn", FAIL = "fail";

    static final double WORDS_FAIL = 0.30, WORDS_WARN = 0.15;
    static final double UNTRACED_WARN = 0.20;
    static final int COPY_RUN_FAIL = 100;
    static final double COPY_COVER_WARN = 0.30;
    static final double DUP_FAIL = 0.8, DUP_WARN = 0.6;
    static final int PARA_FAIL = 500, PARA_WARN = 300, PARA_WARN_WECHAT = 150;
    static final int LONG_SENTENCE = 60;
    static final double LONG_SENTENCE_WARN = 0.15;
    static final double FILLER_WARN = 3, DASH_WARN = 3;
    static final int WECHAT_TITLE_FAIL = 64, WECHAT_TITLE_WARN = 30;
    private static final int COPY_GRAM = 8, COPY_SEED = 20, POOL_CHARS = 500_000;

    /** A small built-in list: high-risk terms fail, the rest (advertising-law absolutes and the like) warn. */
    static final Map<String, List<String>> SENSITIVE = Map.of(
            "high", List.of("炸弹制作", "制作炸药", "制毒", "贩毒", "枪支出售", "代开发票", "办假证", "洗钱教程", "赌博网站"),
            "other", List.of("最佳", "国家级", "第一品牌", "100%有效", "根治", "稳赚不赔", "绝对安全", "史上最", "全网最低"));
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "TODO|TBD|\\[图\\s*[0-9XxＸ]*\\]|【图\\s*[0-9XxＸ]*】|[（(]待补[充]?[）)]|待补充|(?i)(?<![a-z])x{3,}(?![a-z])");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"'，。、；）】]+");
    private static final Pattern CITE = Pattern.compile("[\\[［(（]\\s*(S\\d+(?:\\s*[,，、;；]\\s*S\\d+)*)\\s*[\\]］)）]");
    private static final Pattern SOURCE_ID = Pattern.compile("S\\d+");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.A-Za-z])\\d+(?:[.,]\\d+)*%?");
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d\\d");
    private static final Pattern LIST_MARK = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.、)）]\\s*)");
    private static final Pattern QUOTED = Pattern.compile("“[^”]*”|「[^」]*」|\"[^\"]*\"|『[^』]*』");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;]");

    private ArticleChecks() {
    }

    /** What a link answered: an HTTP status, or {@code error} dns | timeout | redirects | blocked: … | io: … */
    public record LinkStatus(int code, String error) {
    }

    /**
     * {@code sources}: each source's text by id (S1…); {@code extra}: text of other materials (uploads) the draft may
     * draw on; {@code fileExists}: whether a local image path is there; {@code probe}: what a link answers.
     */
    public record Input(Brief brief, List<Section> outline, String draft, Map<String, String> sources, String extra,
                        Predicate<String> fileExists, Function<String, LinkStatus> probe, Tech tech, StyleLexicon lexicon) {
        public Input(Brief brief, List<Section> outline, String draft, Map<String, String> sources, String extra,
                     Predicate<String> fileExists, Function<String, LinkStatus> probe, Tech tech) {
            this(brief, outline, draft, sources, extra, fileExists, probe, tech, null);
        }

        public Input(Brief brief, List<Section> outline, String draft, Map<String, String> sources, String extra,
                     Predicate<String> fileExists, Function<String, LinkStatus> probe) {
            this(brief, outline, draft, sources, extra, fileExists, probe, null, null);
        }
    }

    /**
     * What a genre pack adds: its configuration (which checks run, the required sections), the evidence log, and the
     * repository code references are looked up in (null: none). Without it the original checks run, as before.
     */
    public record Tech(GenrePack pack, List<com.superagent.task.harness.HarnessState.Article.Evidence> evidence, java.nio.file.Path repo) {
    }

    /** {@code untraced}: numbers not found in any source, for the fact-checker to look at. */
    public record Result(List<Issue> items, List<String> untraced) {
        public boolean passed() {
            return items.stream().noneMatch(Issue::failed);
        }
    }

    public static Result run(Input in) {
        Doc doc = Doc.of(in.draft());
        String pool = pool(in);
        GenrePack pack = in.tech() == null ? null : in.tech().pack();
        String genre = in.brief() == null ? null : in.brief().genre();
        String wechat = pack == null ? genre : pack.has(GenrePack.WECHAT) ? "公众号" : null;
        List<Issue> out = new ArrayList<>();
        out.addAll(words(doc, in.brief()));
        out.addAll(headings(doc));
        StyleLexicon lex = in.lexicon() == null ? StyleLexicon.FALLBACK : in.lexicon();
        out.addAll(coverage(doc, in.outline()));
        out.addAll(placeholders(doc, in.fileExists()));
        if (pack == null || pack.has(GenrePack.LINKS)) {
            out.addAll(links(doc, in.probe()));
        }
        out.addAll(citations(doc, in.sources().keySet()));
        List<String> untraced = new ArrayList<>();
        if (pack == null) {
            out.addAll(numbers(doc, pool, untraced));
        } else if (pack.has(GenrePack.NUMBERS)) {
            out.addAll(TechChecks.numbers(doc, pool + "\n" + EvidenceLog.pool(in.tech().evidence()), untraced));
        }
        out.addAll(copying(doc, pool));
        out.addAll(duplicates(doc));
        out.addAll(lengths(doc, wechat));
        if (pack == null || !pack.has(GenrePack.BANNED)) {
            out.addAll(filler(doc, lex));
        }
        out.addAll(sensitive(doc));
        out.addAll(wechat(doc, wechat));
        if (pack != null) {
            Tech t = in.tech();
            out.addAll(TechChecks.requiredSections(doc, pack));
            if (pack.has(GenrePack.CODE_REFS)) {
                out.addAll(TechChecks.codeRefs(in.draft(), t.repo()));
            }
            if (pack.has(GenrePack.EVIDENCE_IDS)) {
                out.addAll(TechChecks.evidenceIds(in.draft(), t.evidence()));
            }
            if (pack.has(GenrePack.MERMAID)) {
                out.addAll(MermaidCheck.check(in.draft()));
            }
            if (pack.has(GenrePack.CONCRETENESS)) {
                out.addAll(TechChecks.concreteness(in.draft(), lex));
            }
            if (pack.has(GenrePack.BANNED)) {
                out.addAll(TechChecks.bannedPhrases(doc, pack.bannedPhrases(), lex));
            }
        }
        return new Result(out, untraced);
    }

    // --- the checks ---

    static List<Issue> words(Doc doc, Brief brief) {
        int n = count(doc.prose());
        if (brief == null || brief.words() <= 0) {
            return List.of(new Issue("字数", PASS, null, n + " 字（没有目标字数）"));
        }
        double off = Math.abs(n - brief.words()) / (double) brief.words();
        String note = n + " 字，目标 " + brief.words() + " 字（偏离 " + Math.round(off * 100) + "%）";
        return List.of(new Issue("字数", off > WORDS_FAIL ? FAIL : off > WORDS_WARN ? WARN : PASS, null, note));
    }

    static List<Issue> headings(Doc doc) {
        return issues(TextChecks.headings(doc.text, FAIL));
    }

    static List<Issue> issues(List<TextChecks.Finding> found) {
        return found.stream().map(f -> new Issue(f.check(), f.level(), f.where(), f.note())).toList();
    }

    static List<Issue> coverage(Doc doc, List<Section> outline) {
        if (outline == null || outline.isEmpty()) {
            return List.of();
        }
        List<Heading> sections = doc.sectionHeadings();
        List<Issue> out = new ArrayList<>();
        for (Section s : outline) {
            String want = normTitle(s.title());
            if (sections.stream().noneMatch(h -> similar(normTitle(h.text), want))) {
                out.add(new Issue("小节覆盖", FAIL, s.title(), "提纲里的这一节正文里找不到"));
            }
        }
        for (Heading h : sections) {
            String got = normTitle(h.text);
            if (outline.stream().noneMatch(s -> similar(got, normTitle(s.title())))) {
                out.add(new Issue("小节覆盖", WARN, h.text, "提纲里没有这一节"));
            }
        }
        return out.isEmpty() ? List.of(new Issue("小节覆盖", PASS, null, "提纲的 " + outline.size() + " 节都在")) : out;
    }

    static List<Issue> placeholders(Doc doc, Predicate<String> fileExists) {
        List<Issue> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(IMAGE.matcher(doc.text).replaceAll("")); // an image's alt text isn't a placeholder
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            seen.add(m.group());
        }
        seen.forEach(p -> out.add(new Issue("占位符", FAIL, p, "稿件里还留着占位符")));
        Matcher img = IMAGE.matcher(doc.text);
        while (img.find()) {
            String path = img.group(1);
            if (!path.startsWith("http") && !path.startsWith("data:") && (fileExists == null || !fileExists.test(path))) {
                out.add(new Issue("占位符", FAIL, path, "引用的图片不存在"));
            }
        }
        return out.isEmpty() ? List.of(new Issue("占位符", PASS, null, "没有占位符")) : out;
    }

    static List<Issue> links(Doc doc, Function<String, LinkStatus> probe) {
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = URL.matcher(doc.text);
        while (m.find()) {
            urls.add(m.group().replaceAll("[.,;:!?]+$", ""));
        }
        if (urls.isEmpty() || probe == null) {
            return List.of(new Issue("链接", PASS, null, "没有链接"));
        }
        List<Issue> out = new ArrayList<>();
        for (String u : urls) {
            LinkStatus st = probe.apply(u);
            String level = linkLevel(st);
            if (!level.equals(PASS)) {
                out.add(new Issue("链接", level, u, st.error() != null ? linkError(st.error()) : "返回 " + st.code()));
            }
        }
        return out.isEmpty() ? List.of(new Issue("链接", PASS, null, urls.size() + " 个链接都能打开")) : out;
    }

    static String linkLevel(LinkStatus st) {
        if (st.error() != null) {
            return st.error().equals("dns") ? FAIL : WARN;
        }
        if (st.code() == 404 || st.code() == 410) {
            return FAIL;
        }
        return st.code() >= 400 ? WARN : PASS;
    }

    private static String linkError(String error) {
        return switch (error) {
            case "dns" -> "域名解析失败";
            case "timeout" -> "超时";
            case "redirects" -> "重定向超过 5 次";
            default -> error.startsWith("blocked:") ? "平台不访问这个地址（" + error.substring(8).strip() + "）"
                    : "打不开（" + error.replaceFirst("^io:\\s*", "") + "）";
        };
    }

    static List<Issue> citations(Doc doc, Set<String> known) {
        Set<String> cited = new LinkedHashSet<>();
        Matcher m = CITE.matcher(doc.text);
        while (m.find()) {
            Matcher id = SOURCE_ID.matcher(m.group(1));
            while (id.find()) {
                cited.add(id.group());
            }
        }
        List<Issue> out = new ArrayList<>();
        cited.stream().filter(c -> !known.contains(c))
                .forEach(c -> out.add(new Issue("引用编号", FAIL, c, "资料清单里没有 " + c)));
        known.stream().filter(k -> !cited.contains(k))
                .forEach(k -> out.add(new Issue("引用编号", WARN, k, "资料 " + k + " 一次都没被引用")));
        return out.isEmpty() ? List.of(new Issue("引用编号", PASS, null, "引用 " + cited.size() + " 份资料")) : out;
    }

    static List<Issue> numbers(Doc doc, String pool, List<String> untraced) {
        if (pool.isBlank()) {
            return List.of();
        }
        String hay = pool.replace(",", "");
        Set<String> found = new LinkedHashSet<>();
        for (String line : doc.proseLines()) {
            String l = CITE.matcher(LIST_MARK.matcher(line).replaceFirst("")).replaceAll("");
            l = URL.matcher(l).replaceAll("");
            Matcher m = NUMBER.matcher(l);
            while (m.find()) {
                String n = m.group();
                String bare = n.replace(",", "").replace("%", "");
                if (bare.length() < 2 || YEAR.matcher(bare).matches() && bare.length() == 4) {
                    continue; // single digits and years: too common to trace
                }
                found.add(n);
            }
        }
        if (found.isEmpty()) {
            return List.of(new Issue("数字溯源", PASS, null, "没有要溯源的数字"));
        }
        for (String n : found) {
            if (!hay.contains(n.replace(",", ""))) {
                untraced.add(n);
            }
        }
        double ratio = untraced.size() / (double) found.size();
        String note = found.size() + " 个数字，" + untraced.size() + " 个在资料里找不到"
                + (untraced.isEmpty() ? "" : "：" + String.join("、", untraced.stream().limit(10).toList()));
        return List.of(new Issue("数字溯源", ratio > UNTRACED_WARN ? WARN : PASS, null, note));
    }

    static List<Issue> copying(Doc doc, String pool) {
        if (pool.isBlank()) {
            return List.of();
        }
        String src = squash(pool.length() > POOL_CHARS ? pool.substring(0, POOL_CHARS) : pool);
        String own = squash(QUOTED.matcher(String.join("\n", doc.proseLines().stream()
                .filter(l -> !l.stripLeading().startsWith(">")).toList())).replaceAll(""));
        if (own.length() < COPY_GRAM || src.length() < COPY_GRAM) {
            return List.of(new Issue("照搬资料", PASS, null, "没有照搬"));
        }
        Set<Long> seeds = grams(src, COPY_SEED);
        int run = 0, longest = 0, at = -1;
        for (int i = 0; i + COPY_SEED <= own.length(); i++) {
            if (seeds.contains(hash(own, i, COPY_SEED))) {
                run++;
                if (run + COPY_SEED - 1 > longest) {
                    longest = run + COPY_SEED - 1;
                    at = i - run + 1;
                }
            } else {
                run = 0;
            }
        }
        List<Issue> out = new ArrayList<>();
        if (longest > COPY_RUN_FAIL) {
            out.add(new Issue("照搬资料", FAIL, clip(own.substring(at, Math.min(own.length(), at + 30))),
                    "和资料原文连续 " + longest + " 字相同（引号里的不算）"));
        }
        Set<Long> small = grams(src, COPY_GRAM);
        boolean[] covered = new boolean[own.length()];
        for (int i = 0; i + COPY_GRAM <= own.length(); i++) {
            if (small.contains(hash(own, i, COPY_GRAM))) {
                for (int j = i; j < i + COPY_GRAM; j++) {
                    covered[j] = true;
                }
            }
        }
        int c = 0;
        for (boolean b : covered) {
            c += b ? 1 : 0;
        }
        double cover = c / (double) own.length();
        if (cover > COPY_COVER_WARN) {
            out.add(new Issue("照搬资料", WARN, null, "正文 " + Math.round(cover * 100) + "% 的内容和资料原文的片段相同"));
        }
        return out.isEmpty() ? List.of(new Issue("照搬资料", PASS, null, "和资料的重合 " + Math.round(cover * 100) + "%")) : out;
    }

    static List<Issue> duplicates(Doc doc) {
        List<String> paras = doc.paragraphs().stream().map(ArticleChecks::squash).filter(p -> p.length() >= 30).toList();
        List<Set<String>> grams = paras.stream().map(ArticleChecks::fourGrams).toList();
        List<Issue> out = new ArrayList<>();
        for (int i = 0; i < paras.size(); i++) {
            for (int j = i + 1; j < paras.size(); j++) {
                double jac = jaccard(grams.get(i), grams.get(j));
                if (jac >= DUP_WARN) {
                    out.add(new Issue("段落重复", jac >= DUP_FAIL ? FAIL : WARN, "第 " + (i + 1) + " 段和第 " + (j + 1) + " 段",
                            "相似度 " + Math.round(jac * 100) + "%：「" + clip(paras.get(i)) + "」"));
                }
            }
        }
        return out.isEmpty() ? List.of(new Issue("段落重复", PASS, null, "没有重复段落")) : out;
    }

    static List<Issue> lengths(Doc doc, String genre) {
        int warnAt = "公众号".equals(genre) ? PARA_WARN_WECHAT : PARA_WARN;
        List<Issue> out = new ArrayList<>();
        int sentences = 0, long_ = 0;
        List<String> paras = doc.paragraphs();
        for (int i = 0; i < paras.size(); i++) {
            String p = squash(paras.get(i));
            int n = p.length();
            if (n > PARA_FAIL) {
                out.add(new Issue("段落长度", FAIL, "第 " + (i + 1) + " 段：「" + clip(p) + "」", n + " 字，超过 " + PARA_FAIL));
            } else if (n > warnAt) {
                out.add(new Issue("段落长度", WARN, "第 " + (i + 1) + " 段：「" + clip(p) + "」", n + " 字，超过 " + warnAt));
            }
            for (String s : SENTENCE_END.split(paras.get(i))) {
                int len = squash(s).length();
                if (len > 0) {
                    sentences++;
                    long_ += len > LONG_SENTENCE ? 1 : 0;
                }
            }
        }
        if (sentences > 0 && long_ / (double) sentences > LONG_SENTENCE_WARN) {
            out.add(new Issue("句子长度", WARN, null, "超过 " + LONG_SENTENCE + " 字的长句占 "
                    + Math.round(long_ * 100.0 / sentences) + "%"));
        }
        return out.isEmpty() ? List.of(new Issue("段落和句子长度", PASS, null, "段落和句子长度合适")) : out;
    }

    static List<Issue> filler(Doc doc) {
        return filler(doc, StyleLexicon.FALLBACK);
    }

    static List<Issue> filler(Doc doc, StyleLexicon lex) {
        return issues(TextChecks.filler(String.join("\n", doc.proseLines()), lex));
    }

    static List<Issue> sensitive(Doc doc) {
        List<Issue> out = new ArrayList<>();
        SENSITIVE.getOrDefault("high", List.of()).stream().filter(doc.text::contains)
                .forEach(w -> out.add(new Issue("敏感词", FAIL, w, "高危词")));
        SENSITIVE.getOrDefault("other", List.of()).stream().filter(doc.text::contains)
                .forEach(w -> out.add(new Issue("敏感词", WARN, w, "绝对化或需要谨慎的用语")));
        return out.isEmpty() ? List.of(new Issue("敏感词", PASS, null, "没有命中")) : out;
    }

    static List<Issue> wechat(Doc doc, String genre) {
        if (!"公众号".equals(genre) || doc.headings.isEmpty()) {
            return List.of();
        }
        String title = doc.headings.get(0).text;
        int len = title.codePointCount(0, title.length());
        if (len > WECHAT_TITLE_FAIL) {
            return List.of(new Issue("公众号标题", FAIL, title, len + " 字，超过 " + WECHAT_TITLE_FAIL));
        }
        if (len > WECHAT_TITLE_WARN) {
            return List.of(new Issue("公众号标题", WARN, title, len + " 字，超过 " + WECHAT_TITLE_WARN + " 字会在朋友圈被截断"));
        }
        return List.of(new Issue("公众号标题", PASS, null, len + " 字"));
    }

    // --- the draft, read once ---

    record Heading(int level, String text) {
    }

    /** The draft's lines with code blocks set aside, its headings and paragraphs. */
    static final class Doc {
        final String text;
        final List<String> lines = new ArrayList<>(); // outside code blocks
        final List<Heading> headings = new ArrayList<>();

        private Doc(String text) {
            this.text = text;
        }

        static Doc of(String draft) {
            String text = draft == null ? "" : draft;
            Doc d = new Doc(text.replaceAll("(?s)```.*?```", ""));
            for (String l : d.text.lines().toList()) {
                d.lines.add(l);
                Matcher h = HEADING.matcher(l);
                if (h.matches()) {
                    d.headings.add(new Heading(h.group(1).length(), h.group(2).strip()));
                }
            }
            return d;
        }

        List<String> proseLines() {
            return lines.stream().filter(l -> !l.isBlank() && !HEADING.matcher(l).matches()).toList();
        }

        String prose() {
            return String.join("\n", proseLines());
        }

        List<String> paragraphs() {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String l : lines) {
                boolean breaks = l.isBlank() || HEADING.matcher(l).matches() || LIST_MARK.matcher(l).find()
                        || l.stripLeading().startsWith("|");
                if (breaks) {
                    if (!cur.isEmpty()) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                    if (!l.isBlank() && !HEADING.matcher(l).matches()) {
                        out.add(l); // a list item or table row is its own block
                    }
                } else {
                    cur.append(l.strip());
                }
            }
            if (!cur.isEmpty()) {
                out.add(cur.toString());
            }
            return out;
        }

        /** The section headings: the top level under a single title (H1), or the top level when there is none. */
        List<Heading> sectionHeadings() {
            long h1 = headings.stream().filter(h -> h.level == 1).count();
            List<Heading> under = h1 == 1 ? headings.stream().filter(h -> h.level > 1).toList() : headings;
            int top = under.stream().mapToInt(Heading::level).min().orElse(2);
            return under.stream().filter(h -> h.level == top).toList();
        }
    }

    // --- helpers ---

    private static String pool(Input in) {
        StringBuilder sb = new StringBuilder();
        in.sources().values().forEach(t -> sb.append(t == null ? "" : t).append('\n'));
        if (in.extra() != null) {
            sb.append(in.extra());
        }
        return sb.toString();
    }

    /** Han characters and Latin words, the way Chinese writing is counted. */
    static int count(String text) {
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

    /** Without whitespace, punctuation or Markdown marks: what copying and duplicate checks compare. */
    static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\p{P}\\p{S}]", "").toLowerCase(Locale.ROOT);
    }

    static String normTitle(String t) {
        return squash(t.replaceAll("^\\s*(?:第[一二三四五六七八九十\\d]+[章节部分]|[一二三四五六七八九十]+[、.]|\\d+(?:\\.\\d+)*[.、)）]?)\\s*", ""));
    }

    private static boolean similar(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static Set<String> fourGrams(String s) {
        Set<String> g = new HashSet<>();
        for (int i = 0; i + 4 <= s.length(); i++) {
            g.add(s.substring(i, i + 4));
        }
        return g;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String x : a) {
            inter += b.contains(x) ? 1 : 0;
        }
        return inter / (double) (a.size() + b.size() - inter);
    }

    private static Set<Long> grams(String s, int n) {
        Set<Long> g = new HashSet<>();
        for (int i = 0; i + n <= s.length(); i++) {
            g.add(hash(s, i, n));
        }
        return g;
    }

    private static long hash(String s, int from, int n) {
        long h = 1125899906842597L;
        for (int i = from; i < from + n; i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    private static int occurrences(String text, String w) {
        int n = 0;
        for (int i = text.indexOf(w); i >= 0; i = text.indexOf(w, i + w.length())) {
            n++;
        }
        return n;
    }

    static String clip(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}
