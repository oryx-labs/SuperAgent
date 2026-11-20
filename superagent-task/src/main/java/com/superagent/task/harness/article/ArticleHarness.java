package com.superagent.task.harness.article;

import com.superagent.core.task.Task;
import com.superagent.files.DocumentText;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import com.superagent.task.harness.HarnessState.Article;
import com.superagent.task.harness.HarnessState.Article.Issue;
import com.superagent.task.harness.HarnessState.Article.Item;
import com.superagent.task.harness.HarnessState.Article.Response;
import com.superagent.task.harness.HarnessState.Status;
import com.superagent.task.harness.TaskHarness;
import com.superagent.task.harness.general.GeneralPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The writing flow (long-form: articles, reports, technical pieces, lecture notes): brief → sources → outline (the
 * one confirmation the user always gives) → draft → check (the platform's own, no model) → review (editor on the
 * task's model, fact-checker and reader on the cheaper one, each in a conversation of its own) → revise → deliver.
 * A draft that doesn't pass goes back by kind of problem: wording to revise, the outline itself to the outline (once,
 * confirmed again), too little evidence to the sources (once); two rounds of fixes each for checks and reviews, then
 * the user decides. The author works in the task's own session throughout.
 */
@Component
public class ArticleHarness implements TaskHarness {

    private static final Logger log = LoggerFactory.getLogger(ArticleHarness.class);

    public static final String KIND = "article";
    static final String BRIEF = "brief", RESEARCH = "research", OUTLINE = "outline", DRAFT = "draft", CHECK = "check",
            REVIEW = "review", REVISE = "revise", DELIVER = "deliver";
    /** Rounds of fixes after failed checks, and returns after failed reviews, before the user is asked. */
    public static final int RETURNS = 2;
    /** Longer than this (target characters) and the draft is written section by section. */
    static final int SECTIONED = 3000;
    static final String SOURCES_DIR = "sources", UPLOADS_DIR = "uploads";
    private static final String INTRODUCED = "introduced", SECTIONS_DONE = "article.sections", WAIVED_LINKS = "article.waivedLinks";
    private static final Pattern SOURCE_FILE = Pattern.compile("(S\\d+)(?:[._\\-].*)?");
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final int EXTRA_CHARS = 400_000;

    private final Function<String, ArticleChecks.LinkStatus> probe;
    private final GenrePacks packs;

    /** Without a company's genre packs the original flow runs (tests that don't care about genres). */
    public ArticleHarness() {
        this(new LinkProbe(), GenrePacks.none());
    }

    @Autowired
    public ArticleHarness(GenrePacks packs) {
        this(new LinkProbe(), packs);
    }

    ArticleHarness(Function<String, ArticleChecks.LinkStatus> probe) {
        this(probe, GenrePacks.none());
    }

    ArticleHarness(Function<String, ArticleChecks.LinkStatus> probe, GenrePacks packs) {
        this.probe = probe;
        this.packs = packs;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean usesTaskSession() {
        return true;
    }

    @Override
    public boolean reopens() {
        return true;
    }

    @Override
    public HarnessState start(Task task, String delivery) {
        return start(task, delivery, null);
    }

    @Override
    public HarnessState start(Task task, String delivery, String genre) {
        HarnessState s = new HarnessState();
        s.harness = KIND;
        s.steps = new ArrayList<>(List.of(new HarnessState.Step(BRIEF, "需求"), new HarnessState.Step(RESEARCH, "资料"),
                new HarnessState.Step(OUTLINE, "提纲"), new HarnessState.Step(DRAFT, "初稿"), new HarnessState.Step(CHECK, "核对"),
                new HarnessState.Step(REVIEW, "评审"), new HarnessState.Step(REVISE, "修改"), new HarnessState.Step(DELIVER, "交付")));
        s.article = new Article();
        s.article.genre = genre;
        s.article.next = BRIEF;
        return s;
    }

    @Override
    public void drive(HarnessRun run) {
        HarnessState s = run.state();
        if (s.article == null) {
            s.article = new Article();
            s.article.next = s.finished() ? null : BRIEF;
        }
        Article a = s.article;
        String reply = run.reply();
        if (a.next == null && reply != null) {
            reopen(s, reply);
            reply = null;
            run.save();
        }
        while (!run.settled()) {
            if (a.next == null) {
                run.finish(output(run.task(), a, draftFile(run)));
                return;
            }
            HarnessState.Step step = s.step(a.next);
            if (run.cancelled()) {
                run.stopped(step);
                return;
            }
            boolean waiting = a.waitKind != null && step.key.equals(s.waitingOn);
            String answer = waiting ? reply : null;
            if (reply != null && answer == null) {
                a.hint = reply; // said while not asked: it still goes into the next turn
            }
            reply = null;
            if (waiting && answer == null) {
                run.waitFor(step, step.note); // picked up again (a restart) while still waiting: the same question
                return;
            }
            String kind = answer != null ? a.waitKind : null;
            a.waitKind = null;
            try {
                switch (step.key) {
                    case BRIEF -> brief(run, step, answer, kind);
                    case RESEARCH -> research(run, step, answer, kind);
                    case OUTLINE -> outline(run, step, answer, kind);
                    case DRAFT -> draft(run, step, answer);
                    case CHECK -> check(run, step, answer);
                    case REVIEW -> review(run, step, answer);
                    case REVISE -> revise(run, step, answer);
                    case DELIVER -> deliver(run, step);
                    default -> throw new IllegalStateException("Unknown step " + step.key);
                }
            } catch (RuntimeException e) {
                log.warn("task {}: article step {} failed", run.task().id(), step.key, e);
                run.fail(step, step.name + "这一步出错：" + e.getMessage());
            }
        }
    }

    // --- ① brief ---

    private void brief(HarnessRun run, HarnessState.Step step, String answer, String kind) {
        Article a = run.state().article;
        if ("brief".equals(kind) && GeneralPrompts.approves(answer) && a.brief != null) {
            run.note("你确认采用建议值");
            finishBrief(run, step);
            return;
        }
        run.begin(step);
        resolvePack(run, a);
        String format = a.pack == null ? ArticlePrompts.BRIEF : ArticlePrompts.briefPrompt(a.pack, a.guide);
        String prompt = answer != null ? "\n\n用户的答复：\n" + answer + format : format;
        ArticlePrompts.ParsedBrief p = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HarnessHost.Turn turn = turn(run, prompt, BRIEF);
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            if (asked(run, step, turn)) {
                return;
            }
            if (a.pack != null) {
                switchPack(run, turn.text());
            }
            p = a.pack == null ? ArticlePrompts.parseBrief(turn.text()) : ArticlePrompts.parseBrief(turn.text(), a.pack);
            if (p.parsed() && p.problems().isEmpty() && p.suggested().isEmpty()) {
                break;
            }
            if (!p.parsed() || !p.problems().isEmpty()) {
                prompt = ArticlePrompts.retry("写作需求", p.problems(), ArticlePrompts.BRIEF);
            } else {
                break; // only fields it couldn't infer: the user is asked, not the agent again
            }
        }
        if (!p.parsed()) {
            run.fail(step, "写作需求两次都没按格式输出：" + String.join("；", p.problems()));
            return;
        }
        a.brief = p.brief();
        if (!p.suggested().isEmpty() || !p.problems().isEmpty()) {
            wait(run, step, "brief", ArticlePrompts.briefAsk(p.suggested(), p.problems()));
            return;
        }
        finishBrief(run, step);
    }

    /** The genre pack the task runs by: the one asked for, else the company's fallback; none at all keeps the original flow. */
    private void resolvePack(HarnessRun run, Article a) {
        if (a.pack != null) {
            return;
        }
        Optional<GenrePacks.Loaded> found = packs.find(a.genre);
        if (found.isEmpty() && a.genre != null && !a.genre.isBlank()) {
            found = packs.fallback();
            if (found.isPresent()) {
                run.note("没有「" + a.genre + "」这个文体，按" + GenrePacks.FALLBACK + "处理");
            }
        } else if (found.isEmpty()) {
            found = packs.fallback();
        }
        found.ifPresent(l -> {
            a.pack = l.pack();
            a.guide = l.guide();
        });
    }

    /** The author may say the task is better written in another of the company's genres: it is taken up at once. */
    private void switchPack(HarnessRun run, String reply) {
        Article a = run.state().article;
        String g = ArticlePrompts.briefField(reply, "文体");
        if (g == null || g.isBlank() || g.contains(a.pack.name())) {
            return;
        }
        packs.find(g).filter(l -> !l.pack().name().equals(a.pack.name())).ifPresent(l -> {
            run.note("文体改为「" + l.pack().name() + "」（原来是「" + a.pack.name() + "」）");
            a.pack = l.pack();
            a.guide = l.guide();
            a.genre = l.pack().name();
        });
    }

    private void finishBrief(HarnessRun run, HarnessState.Step step) {
        Article a = run.state().article;
        if (a.pack != null && a.pack.needsRepo() && repoRoot(run) == null) {
            run.fail(step, "文体「" + a.pack.name() + "」要读代码仓库，但这个任务没有可用的本地仓库：建任务时在「代码位置」填这台服务器上的仓库路径（管理员限定了目录时要在目录之内）");
            return;
        }
        run.done(step, a.brief.genre() + " · " + a.brief.words() + " 字 · 读者：" + a.brief.audience());
        a.next = RESEARCH;
        run.save();
    }

    // --- ② research ---

    private void research(HarnessRun run, HarnessState.Step step, String answer, String kind) {
        HarnessState s = run.state();
        Article a = s.article;
        Task task = run.task();
        if (a.pack != null && a.pack.investigates()) {
            investigate(run, step, answer, kind);
            return;
        }
        boolean goOn = "sources".equals(kind) && (GeneralPrompts.approves(answer) || answer.contains("不用") || answer.contains("继续"));
        if (goOn) {
            List<String> skipped = new ArrayList<>(waivedLinks(s));
            skipped.addAll(run.host().materials(task).unreadable());
            s.data.put(WAIVED_LINKS, String.join("\n", skipped));
            a.sources = a.sources.stream().map(x -> "读不到".equals(x.status())
                    ? new Article.Source(x.id(), x.title(), x.ref(), "不用", x.summary()) : x).toList();
            run.note("你确认不用读不到的资料，继续");
            finishResearch(run, step);
            return;
        }
        run.begin(step, step.name + (a.researchReturns > 0 ? " · 补充" : ""));
        String prompt = answer != null ? "\n\n用户的答复：\n" + answer + ArticlePrompts.research(a)
                : a.researchReturns > 0 && !a.pending.isEmpty() ? ArticlePrompts.researchMore(a, a.pending)
                : withHint(a, ArticlePrompts.research(a));
        boolean given = materialsGiven(run);
        for (int attempt = 0; attempt < 2; attempt++) {
            HarnessHost.Turn turn = turn(run, prompt, RESEARCH);
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            if (asked(run, step, turn)) {
                return;
            }
            ArticlePrompts.Research r = ArticlePrompts.parseResearch(turn.text());
            if (!r.parsed() && given) {
                if (attempt == 0) {
                    prompt = ArticlePrompts.retry("资料整理", List.of("没有找到「## 资料清单」和「## 主张与证据」"), ArticlePrompts.research(a));
                    continue;
                }
                run.fail(step, "资料整理两次都没按格式输出");
                return;
            }
            a.sources = r.sources();
            a.claims = r.claims();
            a.judgement = r.judgement();
            List<String> unreadable = unreadable(run, a);
            List<String> problems = ArticlePrompts.researchProblems(r, a.brief == null ? null : a.brief.genre(), given);
            if (unreadable.isEmpty() && problems.isEmpty()) {
                finishResearch(run, step);
                return;
            }
            if (attempt == 1) {
                wait(run, step, "sources", ArticlePrompts.sourcesAsk(unreadable, problems));
                return;
            }
            List<String> all = new ArrayList<>(unreadable.stream().map(u -> "读不到：" + u + "（再用 read_link 或 file_get 读一次）").toList());
            all.addAll(problems);
            prompt = ArticlePrompts.retry("资料整理", all, "");
        }
    }

    /** Research by investigating: the author reads the repository and materials and keeps an evidence log; no log, no outline. */
    private void investigate(HarnessRun run, HarnessState.Step step, String answer, String kind) {
        Article a = run.state().article;
        Path repo = repoRoot(run);
        if ("sources".equals(kind) && (GeneralPrompts.approves(answer) || answer.contains("继续")) && !a.evidence.isEmpty()) {
            run.note("你确认带着证据日志里的问题继续");
            finishInvestigation(run, step);
            return;
        }
        run.begin(step, step.name + (a.researchReturns > 0 ? " · 补充" : ""));
        boolean more = a.researchReturns > 0 && !a.pending.isEmpty() && answer == null;
        String prompt = answer != null ? "\n\n用户的答复：\n" + answer + TechPrompts.investigate(a, repo, false)
                : withHint(a, TechPrompts.investigate(a, repo, more));
        for (int attempt = 0; attempt < 2; attempt++) {
            HarnessHost.Turn turn = turn(run, prompt, RESEARCH);
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            if (asked(run, step, turn)) {
                return;
            }
            List<Article.Evidence> log = EvidenceLog.parse(turn.text());
            List<String> problems = EvidenceLog.problems(log, repo);
            a.evidence = log;
            a.judgement = TechPrompts.judgement(turn.text());
            if (problems.isEmpty()) {
                finishInvestigation(run, step);
                return;
            }
            if (attempt == 1) {
                if (log.isEmpty()) {
                    run.fail(step, "调研两次都没有给出证据日志，不能进入提纲：" + problems.get(0));
                    return;
                }
                wait(run, step, "sources", TechPrompts.evidenceAsk(problems));
                return;
            }
            prompt = ArticlePrompts.retry("证据日志", problems, "");
        }
    }

    private void finishInvestigation(HarnessRun run, HarnessState.Step step) {
        Article a = run.state().article;
        run.done(step, a.evidence.size() + " 条证据（" + a.evidence.stream().filter(e -> "代码".equals(e.type())).count() + " 条代码、"
                + a.evidence.stream().filter(e -> "命令".equals(e.type())).count() + " 条命令）");
        a.next = a.researchReturns > 0 && a.outlineApproved && a.draftFileId != null ? DRAFT : OUTLINE;
        run.save();
    }

    private void finishResearch(HarnessRun run, HarnessState.Step step) {
        Article a = run.state().article;
        long cached = a.sources.stream().filter(x -> sourceTexts(run.task()).containsKey(x.id())).count();
        run.done(step, a.sources.size() + " 份资料（" + cached + " 份有原文）· " + a.claims.size() + " 条主张");
        // After a return for more evidence, the confirmed outline stands: the affected sections are rewritten.
        a.next = a.researchReturns > 0 && a.outlineApproved && a.draftFileId != null ? DRAFT : OUTLINE;
        run.save();
    }

    // --- ③ outline ---

    private void outline(HarnessRun run, HarnessState.Step step, String answer, String kind) {
        Article a = run.state().article;
        Task task = run.task();
        if ("outline".equals(kind) && GeneralPrompts.approves(answer)) {
            approveOutline(run, step, "你确认了提纲");
            return;
        }
        boolean again = a.outlineReturns > 0;
        run.begin(step, step.name + (a.approvals.isEmpty() ? "" : " · 修订"));
        String prompt = "outline".equals(kind) ? ArticlePrompts.outlineRevise(answer, a)
                : answer != null ? "\n\n用户的答复：\n" + answer + ArticlePrompts.outline(a)
                : again && !a.pending.isEmpty() && !a.outlineApproved ? ArticlePrompts.outlineReturn(a.pending, a)
                : withHint(a, ArticlePrompts.outline(a));
        ArticlePrompts.Outline o = null;
        List<String> problems = List.of();
        for (int attempt = 0; attempt < 2; attempt++) {
            HarnessHost.Turn turn = turn(run, prompt, OUTLINE);
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            if (asked(run, step, turn)) {
                return;
            }
            o = ArticlePrompts.parseOutline(turn.text());
            problems = o.parsed() ? ArticlePrompts.outlineProblems(o, a.brief, !a.sources.isEmpty() || !a.evidence.isEmpty(), a.pack) : List.of("没有找到「## 备选标题」和「### 小节标题 | 字数」");
            if (problems.isEmpty()) {
                break;
            }
            prompt = ArticlePrompts.retry("提纲", problems, ArticlePrompts.OUTLINE_FORMAT);
        }
        if (!o.parsed()) {
            run.fail(step, "提纲两次都没按格式输出");
            return;
        }
        a.outline = o.text();
        a.titles = o.titles();
        a.thesis = o.thesis();
        a.sections = o.sections();
        a.outlineApproved = false;
        if (run.host().onItsOwn(task)) {
            approveOutline(run, step, "自动确认（任务独立运行）");
            return;
        }
        wait(run, step, "outline", ArticlePrompts.outlineAsk(a, problems, again));
    }

    private void approveOutline(HarnessRun run, HarnessState.Step step, String note) {
        Article a = run.state().article;
        a.outlineApproved = true;
        a.approvals.add(Instant.now());
        a.approvedBy = run.task().createdBy();
        run.note(note);
        run.done(step, note + (a.approvals.size() > 1 ? "（第 " + a.approvals.size() + " 次）" : ""));
        a.next = DRAFT;
        run.save();
    }

    // --- ④ draft ---

    private void draft(HarnessRun run, HarnessState.Step step, String answer) {
        HarnessState s = run.state();
        Article a = s.article;
        String title = title(run.task(), a);
        boolean redo = a.draftFileId != null;
        run.begin(step, step.name + (redo ? " · 重写受影响的小节" : ""));
        HarnessHost.Turn turn;
        if (answer != null) {
            turn = turn(run, "\n\n用户的答复：\n" + answer + (redo ? ArticlePrompts.redraft(a, a.pending, title) : ArticlePrompts.draft(a, title)), DRAFT);
        } else if (redo) {
            turn = turn(run, withHint(a, ArticlePrompts.redraft(a, a.pending, title)), DRAFT);
        } else if (a.brief != null && a.brief.words() > SECTIONED && a.sections.size() > 1) {
            int done = Integer.parseInt(s.data.getOrDefault(SECTIONS_DONE, "0"));
            for (int i = done; i < a.sections.size(); i++) {
                run.note("写第 " + (i + 1) + "/" + a.sections.size() + " 节：" + a.sections.get(i).title());
                HarnessHost.Turn t = turn(run, ArticlePrompts.section(a, i), DRAFT);
                if (t.cancelled()) {
                    run.stopped(step);
                    return;
                }
                if (asked(run, step, t)) {
                    return;
                }
                s.data.put(SECTIONS_DONE, String.valueOf(i + 1));
                run.save();
            }
            turn = turn(run, ArticlePrompts.assemble(a, title), DRAFT);
        } else {
            turn = turn(run, withHint(a, ArticlePrompts.draft(a, title)), DRAFT);
        }
        if (turn.cancelled()) {
            run.stopped(step);
            return;
        }
        if (asked(run, step, turn)) {
            return;
        }
        HarnessHost.Produced file = draftFile(run);
        if (file == null) {
            HarnessHost.Turn again = turn(run, ArticlePrompts.noDraft(a, title), DRAFT);
            if (again.cancelled()) {
                run.stopped(step);
                return;
            }
            file = draftFile(run);
            if (file == null) {
                run.fail(step, "没有交付 Markdown 稿件（用 file_write 交付 .md 文件）");
                return;
            }
        }
        a.draftFileId = file.id();
        a.draftName = file.name();
        if (a.draftSnapshot == null) {
            a.draftSnapshot = file.text();
        }
        a.draftHash = hash(file.text());
        a.pending = new ArrayList<>();
        s.data.remove(SECTIONS_DONE);
        run.done(step, file.name() + " · " + ArticleChecks.count(file.text()) + " 字");
        a.next = CHECK;
        run.save();
    }

    // --- ⑤ check ---

    private void check(HarnessRun run, HarnessState.Step step, String answer) {
        Article a = run.state().article;
        if (answer != null) {
            if (GeneralPrompts.approves(answer)) {
                a.checkWaived = true;
                run.note("你确认带着核对的失败项进入评审");
                run.done(step, "带着失败项进入评审");
                a.next = REVIEW;
            } else {
                List<Item> items = new ArrayList<>(List.of(userItem(answer)));
                items.addAll(checkItems(lastCheck(a)));
                toRevise(a, items, "user");
                a.fixRounds = 0;
                run.done(step, "按你的意见再改一轮");
            }
            run.save();
            return;
        }
        int round = a.checks.size() + 1;
        run.begin(step, step.name + (round > 1 ? " · 第 " + round + " 次" : ""));
        HarnessHost.Produced file = draftFile(run);
        if (file == null) {
            run.fail(step, "找不到稿件文件");
            return;
        }
        Task task = run.task();
        Path ws = task.workspaceDir() == null ? null : Path.of(task.workspaceDir());
        ArticleChecks.Tech tech = a.pack == null ? null : new ArticleChecks.Tech(a.pack, a.evidence, repoRoot(run));
        ArticleChecks.Result r = ArticleChecks.run(new ArticleChecks.Input(a.brief, a.sections, file.text(), sourceTexts(task),
                extraText(task), p -> ws != null && Files.exists(ws.resolve(p).normalize()), probe, tech));
        a.untraced = r.untraced();
        a.checks = append(a.checks, new Article.CheckRound(round, Instant.now(), r.passed(), r.items()));
        r.items().stream().filter(i -> !ArticleChecks.PASS.equals(i.level()))
                .forEach(i -> run.note((i.failed() ? "✕ " : "⚠ ") + i.check() + "：" + i.note() + (i.where() == null ? "" : "（" + i.where() + "）")));
        long fails = r.items().stream().filter(Issue::failed).count();
        long warns = r.items().stream().filter(i -> ArticleChecks.WARN.equals(i.level())).count();
        if (r.passed()) {
            a.fixRounds = 0;
            run.done(step, "第 " + round + " 次通过" + (warns > 0 ? " · " + warns + " 项警告" : ""));
            a.next = REVIEW;
        } else if (a.fixRounds < RETURNS) {
            a.fixRounds++;
            toRevise(a, checkItems(lastCheck(a)), "check");
            run.tell("写作核对第 " + round + " 次有 " + fails + " 项失败，退回修改，改完重新核对");
            run.done(step, "第 " + round + " 次 " + fails + " 项失败，退回修改");
        } else {
            wait(run, step, "check", ArticlePrompts.checkStuck(r.items().stream().filter(Issue::failed).toList(), a.fixRounds));
            return;
        }
        run.save();
    }

    // --- ⑥ review ---

    private void review(HarnessRun run, HarnessState.Step step, String answer) {
        Article a = run.state().article;
        Task task = run.task();
        if (answer != null) {
            if (GeneralPrompts.approves(answer)) {
                a.acceptedAsIs = true;
                run.note("你确认照现在的样子交付");
                run.done(step, "你确认照现状交付");
                a.next = DELIVER;
            } else {
                List<Item> items = new ArrayList<>(List.of(userItem(answer)));
                Article.Review last = a.reviews.isEmpty() ? null : a.reviews.get(a.reviews.size() - 1);
                if (last != null) {
                    last.merged().stream().filter(Item::blocking).forEach(items::add);
                }
                toRevise(a, items, "user");
                a.reviewReturns = 0;
                run.done(step, "按你的意见再改一轮");
            }
            run.save();
            return;
        }
        int round = a.reviews.size() + 1;
        run.begin(step, step.name + " · 第 " + round + " 轮");
        HarnessHost.Produced file = draftFile(run);
        if (file == null) {
            run.fail(step, "找不到稿件文件");
            return;
        }
        String draft = file.text();
        Article.CheckRound check = lastCheck(a);
        List<Issue> checkItems = check == null ? List.of() : check.items();

        GenrePack pack = a.pack;
        List<String> roles = pack == null ? List.of(GenrePack.EDITOR, GenrePack.FACT_CHECKER, GenrePack.READER) : pack.reviewers();
        Path repo = repoRoot(run);

        boolean architect = roles.contains(GenrePack.ARCHITECT);
        run.note((architect ? "架构评审" : "编辑评审") + "（新会话 · " + nz(task.model()) + "）");
        String editorPrompt = architect ? TechPrompts.architect(a, draft, checkItems, declined(a), repo)
                : ArticlePrompts.editor(a, draft, checkItems, declined(a));
        ArticlePrompts.Editor editor = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HarnessHost.Turn t = run.turnOn(null, editorPrompt, UUID.randomUUID().toString(), architect ? "review-architect" : "review-editor");
            if (t.cancelled()) {
                run.stopped(step);
                return;
            }
            editor = ArticlePrompts.parseEditor(t.text());
            if (editor.scores().size() == ReviewMerge.DIMENSIONS.size()) {
                break;
            }
            editorPrompt = editorPrompt + "\n\n（上一次评审没有给全 8 个维度的分数，这次每个维度都要一行「维度 N 名称：分数 | 依据 | 建议」。）";
        }

        HarnessHost.Model checker = run.host().checkerModel(task).orElse(null);
        String checkerName = checker == null ? task.model() : checker.model();
        List<Article.Fact> facts = List.of();
        if (roles.contains(GenrePack.FACT_CHECKER)) {
            Map<String, String> sources = sourceTexts(task);
            String pool = String.join("\n", sources.values()) + "\n" + extraText(task);
            run.note("事实核查（新会话 · " + nz(checkerName) + "）");
            HarnessHost.Turn ft = run.turnOn(checker, ArticlePrompts.facts(a, draft, factSources(task, sources), a.untraced),
                    UUID.randomUUID().toString(), "review-facts");
            if (ft.cancelled()) {
                run.stopped(step);
                return;
            }
            facts = ArticlePrompts.verify(ArticlePrompts.parseFacts(ft.text()), pool);
        } else if (roles.contains(GenrePack.VERIFIER)) {
            Map<String, String> material = verifierMaterial(a, draft, repo);
            run.note("复核（新会话 · " + nz(checkerName) + "），平台另外重跑证据日志里的命令");
            HarnessHost.Turn ft = run.turnOn(checker, TechPrompts.verifier(a, draft, material), UUID.randomUUID().toString(), "review-verifier");
            if (ft.cancelled()) {
                run.stopped(step);
                return;
            }
            facts = new ArrayList<>(ArticlePrompts.verify(ArticlePrompts.parseFacts(ft.text()), String.join("\n", material.values())));
            facts.addAll(reproduce(repo, a.evidence));
        }
        long adjusted = facts.stream().filter(Article.Fact::adjusted).count();
        if (adjusted > 0) {
            run.note("核查有 " + adjusted + " 条依据在资料里找不到，改判为「无法核实」");
        }

        ArticlePrompts.Reader reader = new ArticlePrompts.Reader(List.of(), null, List.of());
        List<Item> extra = List.of();
        Boolean consistent = null;
        if (roles.contains(GenrePack.READER)) {
            run.note("读者评审（新会话 · " + nz(checkerName) + "）");
            HarnessHost.Turn rt = run.turnOn(checker, ArticlePrompts.reader(a, draft), UUID.randomUUID().toString(), "review-reader");
            if (rt.cancelled()) {
                run.stopped(step);
                return;
            }
            reader = ArticlePrompts.parseReader(rt.text());
            if (a.thesis != null && reader.retelling() != null && !reader.retelling().isBlank()) {
                HarnessHost.Turn ct = run.turnOn(checker, ArticlePrompts.consistent(a.thesis, reader.retelling()),
                        UUID.randomUUID().toString(), "review-consistency");
                if (ct.cancelled()) {
                    run.stopped(step);
                    return;
                }
                consistent = ArticlePrompts.parseConsistent(ct.text());
            }
        } else if (roles.contains(GenrePack.IMPLEMENTER)) {
            run.note("实现者视角（新会话 · " + nz(checkerName) + "）");
            HarnessHost.Turn it = run.turnOn(checker, TechPrompts.implementer(a, draft), UUID.randomUUID().toString(), "review-implementer");
            if (it.cancelled()) {
                run.stopped(step);
                return;
            }
            TechPrompts.Implementer im = TechPrompts.parseImplementer(it.text());
            extra = TechPrompts.implementerItems(im, pack.maxOpenQuestions());
            reader = new ArticlePrompts.Reader(List.of("能否直接开工：" + (im.can() == null ? "没说" : im.can() ? "能" : "不能")
                    + (im.why() == null || im.why().isBlank() ? "" : "（" + im.why() + "）"), "待澄清问题 " + im.questions().size() + " 个"), null, List.of());
        }

        String weights = pack != null ? pack.weights() : a.brief == null ? null : a.brief.genre();
        int total = ReviewMerge.total(editor.scores(), weights);
        List<Item> merged = ReviewMerge.merge(editor.scores(), editor.findings(), facts, reader.problems(), consistent, extra);
        boolean passed = ReviewMerge.passed(merged, total, facts);
        String route = ReviewMerge.route(passed, merged, facts, a.outlineReturns, a.researchReturns, a.reviewReturns, RETURNS);
        a.reviews = append(a.reviews, new Article.Review(round, Instant.now(), task.model(), checkerName, editor.scores(), total,
                editor.findings(), editor.titles(), facts, reader.answers(), reader.retelling(), consistent, merged, route, passed));
        long blocking = merged.stream().filter(Item::blocking).count();
        run.note("评审第 " + round + " 轮：编辑 " + total + " 分 · " + blocking + " 个要改的问题 · 事实 "
                + facts.stream().filter(f -> "成立".equals(f.verdict())).count() + "/" + facts.size() + " 条成立");
        List<Item> open = merged.stream().filter(Item::blocking).toList();
        switch (route) {
            case ReviewMerge.PASS -> {
                run.done(step, "第 " + round + " 轮通过 · " + total + " 分");
                a.next = DELIVER;
            }
            case ReviewMerge.OUTLINE -> {
                a.outlineReturns++;
                a.outlineApproved = false;
                a.pending = new ArrayList<>(open);
                run.tell("评审认为提纲本身要改（" + firstText(open, "结构") + "），退回提纲，改好后请你再确认一次");
                run.done(step, "第 " + round + " 轮：结构问题，退回提纲");
                reset(run.state(), OUTLINE);
                a.next = OUTLINE;
            }
            case ReviewMerge.RESEARCH -> {
                a.researchReturns++;
                a.pending = new ArrayList<>(open.stream().filter(i -> "事实".equals(i.type())).toList());
                run.tell("评审发现太多说法核实不了，退回补充资料，补完重写受影响的小节");
                run.done(step, "第 " + round + " 轮：证据不足，退回补资料");
                reset(run.state(), RESEARCH);
                a.next = RESEARCH;
            }
            case ReviewMerge.REVISE -> {
                a.reviewReturns++;
                List<Item> items = new ArrayList<>(open);
                merged.stream().filter(i -> !i.blocking()).forEach(items::add);
                toRevise(a, items, "review");
                run.tell("写作评审第 " + round + " 轮没通过（" + total + " 分，" + blocking + " 个问题），退回修改");
                run.done(step, "第 " + round + " 轮未通过，退回修改");
            }
            default -> {
                wait(run, step, "review", ArticlePrompts.reviewStuck(merged, total, a.reviewReturns));
                return;
            }
        }
        run.save();
    }

    // --- ⑦ revise ---

    private void revise(HarnessRun run, HarnessState.Step step, String answer) {
        Article a = run.state().article;
        String title = title(run.task(), a);
        int round = a.revisions.size() + 1;
        run.begin(step, step.name + " · 第 " + round + " 次");
        String hint = answer != null ? answer : a.hint;
        a.hint = null;
        String before = a.draftHash;
        HarnessHost.Turn turn = turn(run, ArticlePrompts.revise(a, a.pending, hint, title), REVISE);
        if (turn.cancelled()) {
            run.stopped(step);
            return;
        }
        if (asked(run, step, turn)) {
            return;
        }
        List<Response> responses = new ArrayList<>(ArticlePrompts.parseResponses(turn.text()));
        HarnessHost.Produced file = draftFile(run);
        boolean changed = file != null && !hash(file.text()).equals(before);
        List<Item> missing = missing(a.pending, responses);
        if (!missing.isEmpty() || !changed) {
            HarnessHost.Turn again = turn(run, ArticlePrompts.reviseMissing(missing, !changed, a, title), REVISE);
            if (again.cancelled()) {
                run.stopped(step);
                return;
            }
            for (Response r : ArticlePrompts.parseResponses(again.text())) {
                responses.removeIf(x -> x.id().equals(r.id()));
                responses.add(r);
            }
            file = draftFile(run);
            changed = file != null && !hash(file.text()).equals(before);
            missing = missing(a.pending, responses);
        }
        a.revisions = append(a.revisions, new Article.Revision(round, Instant.now(), a.revisionFrom, a.pending, responses, changed));
        if (file != null) {
            a.draftHash = hash(file.text());
        }
        long declined = responses.stream().filter(r -> "不改".equals(r.action())).count();
        run.done(step, "回应 " + (a.pending.stream().filter(Item::blocking).count() - missing.size()) + "/"
                + a.pending.stream().filter(Item::blocking).count() + " 项" + (declined > 0 ? " · " + declined + " 项不改" : "")
                + (changed ? "" : " · 稿件没有变化"));
        a.pending = new ArrayList<>();
        a.next = CHECK;
        run.save();
    }

    // --- ⑧ deliver ---

    private void deliver(HarnessRun run, HarnessState.Step step) {
        HarnessState s = run.state();
        Article a = s.article;
        run.begin(step);
        if (draftFile(run) == null) {
            run.fail(step, "找不到终稿文件");
            return;
        }
        for (HarnessState.Step st : s.steps) {
            if (st.status != Status.DONE) {
                st.status = Status.DONE;
                st.note = st == step ? null : st.note == null ? "不需要" : st.note;
            }
        }
        run.done(step, a.acceptedAsIs ? "照现状交付" : "已交付");
        a.next = null;
        run.save();
    }

    static String output(Task task, Article a, HarnessHost.Produced file) {
        StringBuilder sb = new StringBuilder("## ").append(task.title()).append("\n\n");
        if (file != null) {
            sb.append("终稿：").append(file.name()).append("（").append(ArticleChecks.count(file.text() == null ? "" : file.text()))
                    .append(" 字）\n");
        }
        if (a.thesis != null) {
            sb.append("\n").append(a.thesis).append("\n");
        }
        Article.Review r = a.reviews.isEmpty() ? null : a.reviews.get(a.reviews.size() - 1);
        if (r != null) {
            sb.append("\n### 编辑评分 ").append(r.total()).append("/100\n");
            r.scores().forEach(x -> sb.append("- ").append(x.name()).append("：").append(x.score()).append("\n"));
            long ok = r.facts().stream().filter(f -> "成立".equals(f.verdict())).count();
            long unknown = r.facts().stream().filter(f -> "无法核实".equals(f.verdict())).count();
            sb.append("\n### 事实核查\n成立 ").append(ok).append(" 条 · 无法核实 ").append(unknown).append(" 条 · 不成立 ")
                    .append(r.facts().size() - ok - unknown).append(" 条\n");
            List<Item> open = r.merged().stream().filter(Item::blocking).toList();
            if (!r.passed() && !open.isEmpty()) {
                sb.append("\n### 没解决的问题（你确认照现状交付）\n");
                open.forEach(i -> sb.append("- ⚠️ ").append(i.text()).append("\n"));
            }
        }
        Article.CheckRound c = lastCheck(a);
        if (c != null) {
            List<Issue> left = c.items().stream().filter(i -> !ArticleChecks.PASS.equals(i.level())).toList();
            if (!left.isEmpty()) {
                sb.append("\n### 平台核对剩下的").append(c.passed() ? "警告" : "问题").append("\n");
                left.forEach(i -> sb.append("- ").append(i.failed() ? "✕ " : "⚠ ").append(i.check()).append("：").append(i.note()).append("\n"));
            }
        }
        if (!a.sources.isEmpty()) {
            sb.append("\n### 来源\n");
            a.sources.forEach(x -> sb.append("- ").append(x.id()).append(" ").append(x.title())
                    .append(x.ref() == null || x.ref().isBlank() ? "" : "（" + x.ref() + "）").append("\n"));
        }
        sb.append("\n核对 ").append(a.checks.size()).append(" 次 · 评审 ").append(a.reviews.size()).append(" 轮 · 修改 ")
                .append(a.revisions.size()).append(" 次\n");
        return sb.toString();
    }

    // --- technical review helpers ---

    /** What the verifier checks the document against: the evidence log and the lines its code references point at. */
    private static Map<String, String> verifierMaterial(Article a, String draft, Path repo) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("证据日志", EvidenceLog.render(a.evidence));
        List<String> seen = new ArrayList<>();
        List<TechChecks.Ref> refs = new ArrayList<>(TechChecks.refs(draft));
        a.evidence.stream().filter(e -> "代码".equals(e.type())).forEach(e -> refs.addAll(TechChecks.refs(e.key())));
        for (TechChecks.Ref r : refs) {
            String snippet = TechChecks.snippet(repo, r);
            if (snippet != null && !seen.contains(r.text()) && seen.size() < 12) {
                seen.add(r.text());
                out.put("代码 " + r.text(), snippet);
            }
        }
        return out;
    }

    /** The platform re-runs up to three of the log's commands (read-only ones only) and compares the output. */
    static List<Article.Fact> reproduce(Path repo, List<Article.Evidence> log) {
        List<Article.Fact> out = new ArrayList<>();
        for (Article.Evidence e : log.stream().filter(x -> "命令".equals(x.type())).limit(3).toList()) {
            String statement = "命令 `" + e.key() + "` 的输出和证据日志一致";
            if (repo == null) {
                out.add(new Article.Fact(statement, e.id(), "无法核实", "没有本地仓库，没法重跑", false));
                continue;
            }
            ReadOnlyCommands.Result r = ReadOnlyCommands.run(repo, e.key());
            if (!r.ran()) {
                out.add(new Article.Fact(statement, e.id(), "无法核实", "平台没有重跑：" + r.refused(), false));
                continue;
            }
            String after = e.body().replaceFirst("(?s)^.*?输出[：:]?", "");
            java.util.regex.Matcher fenced = java.util.regex.Pattern.compile("(?s)```[^\\n]*\\n(.*?)```").matcher(after);
            String recorded = fenced.find() ? fenced.group(1) : after.split("\\R\\s*\\R", 2)[0]; // the code block, else up to a blank line
            List<String> lines = recorded.lines().map(l -> l.strip().replaceAll("\\s+", " ")).filter(l -> !l.isEmpty()).limit(3).toList();
            String actual = r.output().lines().map(l -> l.strip().replaceAll("\\s+", " ")).collect(java.util.stream.Collectors.joining("\n"));
            boolean same = !lines.isEmpty() && lines.stream().allMatch(actual::contains);
            out.add(new Article.Fact(statement, e.id(), same ? "成立" : "不成立",
                    "重跑的输出：" + (actual.lines().findFirst().orElse("（空）")), false));
        }
        return out;
    }

    /** The task's local repository, when it names one that is there (and inside the company's allowed directories). */
    private static Path repoRoot(HarnessRun run) {
        String repo = run.task().repo();
        if (repo == null || repo.isBlank() || repo.contains("://")) {
            return null;
        }
        Path p = Path.of(repo).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            return null;
        }
        List<Path> roots = run.host().localRoots();
        if (!roots.isEmpty()) {
            try {
                Path real = p.toRealPath();
                if (roots.stream().noneMatch(real::startsWith)) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return p;
    }

    // --- helpers ---

    /** A turn in the task's own session; a conversation that hasn't had this task yet is given the brief first. */
    private HarnessHost.Turn turn(HarnessRun run, String prompt, String label) {
        HarnessState s = run.state();
        boolean first = !s.conversationStarted;
        boolean introduced = s.conversation != null && s.conversation.equals(s.data.get(INTRODUCED));
        String full = introduced ? prompt.strip() : run.host().brief(run.task(), first) + prompt;
        HarnessHost.Turn t = run.turn(full, s.conversation, first, label);
        s.conversationStarted = true;
        if (s.conversation != null) {
            s.data.put(INTRODUCED, s.conversation);
        }
        run.save();
        return t;
    }

    private static boolean asked(HarnessRun run, HarnessState.Step step, HarnessHost.Turn turn) {
        if (!GeneralPrompts.asks(turn.text()) || run.host().onItsOwn(run.task())) {
            return false;
        }
        wait(run, step, "ask", GeneralPrompts.question(turn.text()));
        return true;
    }

    private static void wait(HarnessRun run, HarnessState.Step step, String kind, String ask) {
        run.state().article.waitKind = kind;
        run.waitFor(step, ask);
    }

    /** A message after delivery: another revision with the user's words, checked and reviewed again. */
    private static void reopen(HarnessState s, String reply) {
        Article a = s.article;
        toRevise(a, new ArrayList<>(List.of(userItem(reply))), "user");
        a.reviewReturns = 0;
        a.fixRounds = 0;
        a.acceptedAsIs = false;
        a.checkWaived = false;
        reset(s, REVISE);
        s.step(CHECK).status = Status.PENDING;
        s.step(REVIEW).status = Status.PENDING;
    }

    private static void toRevise(Article a, List<Item> items, String from) {
        List<Item> numbered = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            numbered.add(items.get(i).withId("R" + (i + 1)));
        }
        a.pending = numbered;
        a.revisionFrom = from;
        a.next = REVISE;
    }

    /** Every step from {@code key} on starts over (on the step bar too). */
    private static void reset(HarnessState s, String key) {
        boolean on = false;
        for (HarnessState.Step st : s.steps) {
            on |= st.key.equals(key);
            if (on) {
                st.status = Status.PENDING;
                st.startedAt = null;
                st.finishedAt = null;
                st.note = null;
            }
        }
        s.waitingOn = null;
    }

    private static Item userItem(String text) {
        return new Item(null, "严重", "用户", text, null, "用户", false);
    }

    private static List<Item> checkItems(Article.CheckRound c) {
        if (c == null) {
            return List.of();
        }
        return c.items().stream().filter(i -> !ArticleChecks.PASS.equals(i.level()))
                .map(i -> new Item(null, i.failed() ? "一般" : "建议", "核对", i.check() + "：" + i.note(), i.where(), "平台核对", false))
                .toList();
    }

    private static List<Item> missing(List<Item> pending, List<Response> responses) {
        return pending.stream().filter(Item::blocking).filter(i -> responses.stream().noneMatch(r -> r.id().equals(i.id()))).toList();
    }

    /** What the author declined to change last time, for the editor to rule on. */
    private static List<Response> declined(Article a) {
        if (a.revisions.isEmpty()) {
            return List.of();
        }
        Article.Revision last = a.revisions.get(a.revisions.size() - 1);
        return last.responses().stream().filter(r -> "不改".equals(r.action())).toList();
    }

    private static Article.CheckRound lastCheck(Article a) {
        return a.checks.isEmpty() ? null : a.checks.get(a.checks.size() - 1);
    }

    private static String withHint(Article a, String prompt) {
        if (a.hint == null) {
            return prompt;
        }
        String h = a.hint;
        a.hint = null;
        return "\n\n用户补充：\n" + h + prompt;
    }

    private static String firstText(List<Item> items, String type) {
        return items.stream().filter(i -> type.equals(i.type())).map(Item::text).findFirst().orElse("结构问题");
    }

    private static String title(Task task, Article a) {
        String t = a.titles.isEmpty() ? task.title() : a.titles.get(0);
        return t.replaceAll("[\\\\/:*?\"<>|\\s]+", " ").strip();
    }

    /** The draft in Files: the one it was delivered as, else the largest Markdown or text file the task produced. */
    private static HarnessHost.Produced draftFile(HarnessRun run) {
        Article a = run.state().article;
        List<HarnessHost.Produced> produced = run.host().produced(run.task());
        if (a.draftFileId != null) {
            for (HarnessHost.Produced p : produced) {
                if (p.id().equals(a.draftFileId) && p.text() != null && !p.text().isBlank()) {
                    return p;
                }
            }
        }
        return produced.stream()
                .filter(p -> p.text() != null && !p.text().isBlank()
                        && p.name().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt)$"))
                .max((x, y) -> Integer.compare(x.text().length(), y.text().length())).orElse(null);
    }

    private static boolean materialsGiven(HarnessRun run) {
        String brief = run.task().brief() == null ? "" : run.task().brief();
        return !run.host().materials(run.task()).given().isEmpty() || LINK.matcher(brief).find();
    }

    private static List<String> unreadable(HarnessRun run, Article a) {
        List<String> waived = waivedLinks(run.state());
        List<String> out = new ArrayList<>(run.host().materials(run.task()).unreadable().stream()
                .filter(u -> !waived.contains(u)).toList());
        a.sources.stream().filter(x -> "读不到".equals(x.status()))
                .map(x -> x.id() + " " + x.title() + (x.ref() == null || x.ref().isBlank() ? "" : "（" + x.ref() + "）"))
                .filter(x -> out.stream().noneMatch(x::contains))
                .forEach(out::add);
        return out;
    }

    private static List<String> waivedLinks(HarnessState s) {
        String v = s.data.get(WAIVED_LINKS);
        return v == null || v.isBlank() ? List.of() : List.of(v.split("\n"));
    }

    /** The sources' cached text by id, from sources/S1.md… in the task's workspace. */
    static Map<String, String> sourceTexts(Task task) {
        Map<String, String> out = new LinkedHashMap<>();
        if (task.workspaceDir() == null) {
            return out;
        }
        Path dir = Path.of(task.workspaceDir(), SOURCES_DIR);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var list = Files.list(dir)) {
            list.sorted().forEach(p -> {
                Matcher m = SOURCE_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    String text = read(p);
                    if (text != null && !text.isBlank()) {
                        out.merge(m.group(1), text, (x, y) -> x + "\n" + y);
                    }
                }
            });
        } catch (IOException e) {
            log.debug("could not list {}: {}", dir, e.getMessage());
        }
        return out;
    }

    /** The text of the materials the task was given (uploads/), which a draft may also draw on. */
    static String extraText(Task task) {
        if (task.workspaceDir() == null) {
            return "";
        }
        Path dir = Path.of(task.workspaceDir(), UPLOADS_DIR);
        if (!Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (var list = Files.list(dir)) {
            for (Path p : list.sorted().toList()) {
                if (sb.length() > EXTRA_CHARS) {
                    break;
                }
                String t = read(p);
                if (t != null) {
                    sb.append(t).append('\n');
                }
            }
        } catch (IOException e) {
            log.debug("could not list {}: {}", dir, e.getMessage());
        }
        return sb.toString();
    }

    private static Map<String, String> factSources(Task task, Map<String, String> sources) {
        Map<String, String> out = new LinkedHashMap<>(sources);
        String extra = extraText(task);
        if (!extra.isBlank()) {
            out.put("附件（任务给的文件）", extra);
        }
        return out;
    }

    private static String read(Path p) {
        if (!Files.isRegularFile(p) || !DocumentText.readable(p)) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            DocumentText.pages(p).forEach(pg -> sb.append(pg.text()).append('\n'));
            return sb.toString();
        } catch (IOException | RuntimeException e) {
            log.debug("could not read {}: {}", p, e.getMessage());
            return null;
        }
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> out = new ArrayList<>(list);
        out.add(item);
        return out;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "任务模型" : s;
    }
}
