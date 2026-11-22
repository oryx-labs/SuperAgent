package com.superagent.task.harness.article;

import com.superagent.core.writing.StyleLexicon;
import com.superagent.core.writing.TextChecks;
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
    private static final String CODE_EXT = "java|js|ts|tsx|jsx|py|go|rs|kt|xml|yml|yaml|json|md|sql|sh|css|html|properties|toml|gradle|c|h|cpp|rb|php|swift|cs|vue";

    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"'，。、；）】]+");
    private static final Pattern REF = Pattern.compile("(?<![\\w/.\\-])((?:[\\w.\\-]+/)*[\\w\\-]+(?:\\.[\\w\\-]+)*\\.(?:" + CODE_EXT
            + ")):(\\d+)(?:[-–](\\d+))?(?:#([\\w.$<>]+))?");
    private static final Pattern CITE = Pattern.compile("[\\[［(（]\\s*(E\\d+(?:\\s*[,，、;；]\\s*E\\d+)*)\\s*[\\]］)）]");
    private static final Pattern EVIDENCE_ID = Pattern.compile("E\\d+");
    private static final Pattern FENCE = Pattern.compile("(?s)```.*?```");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");

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

    static List<Issue> concreteness(String draft) {
        return concreteness(draft, StyleLexicon.FALLBACK);
    }

    /** Each top-level section needs something checkable; a vague claim without a number in its sentence fails. */
    static List<Issue> concreteness(String draft, StyleLexicon lex) {
        return ArticleChecks.issues(TextChecks.concreteness(draft, lex, FAIL));
    }

    // --- banned phrases ---

    static List<Issue> bannedPhrases(ArticleChecks.Doc doc, List<String> extra) {
        return bannedPhrases(doc, extra, StyleLexicon.FALLBACK);
    }

    /** Hits with the sentence they sit in; three or more at three per thousand characters fail, any hit warns. */
    static List<Issue> bannedPhrases(ArticleChecks.Doc doc, List<String> extra, StyleLexicon lex) {
        return ArticleChecks.issues(TextChecks.bannedPhrases(doc.prose(), lex, extra, true));
    }
}
