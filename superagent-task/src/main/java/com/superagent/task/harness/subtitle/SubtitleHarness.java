package com.superagent.task.harness.subtitle;

import com.fasterxml.jackson.databind.JsonNode;
import com.superagent.core.media.ModelCapabilities;
import com.superagent.core.media.SpeechModel;
import com.superagent.core.media.SpeechToText;
import com.superagent.core.media.TextTokens;
import com.superagent.core.media.Transcript;
import com.superagent.core.media.TranscriptAligner;
import com.superagent.core.task.Task;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.usage.UsageContext;
import com.superagent.files.DocumentText;
import com.superagent.glossary.GlossaryService;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import com.superagent.task.harness.TaskHarness;
import com.superagent.task.harness.general.GeneralPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subtitle flow, verbatim transcription only (mixed Chinese and English stays as spoken): materials → terms →
 * transcribe (every speech model that can run, side by side, called by the platform) → align and vote (the
 * platform) → calibrate (text models settle only the disputed spans; a writing stands on a majority, else it is a
 * doubt) → check (the platform's subtitle norms; failures go back to a model to re-break, twice at most, then the
 * user decides) → deliver (the srt and the list of doubts, into Files). A person's fix of a doubt afterwards goes
 * into the glossary, so the next subtitles hear it right.
 */
@Component
public class SubtitleHarness implements TaskHarness {

    private static final Logger log = LoggerFactory.getLogger(SubtitleHarness.class);

    public static final String KIND = "subtitle";
    static final String MATERIAL = "material", TERMS = "terms", TRANSCRIBE = "transcribe", ALIGN = "align",
            CALIBRATE = "calibrate", CHECK = "check", DELIVER = "deliver";
    /** How many times failed checks go back to a model before the user is asked. */
    static final int RETURNS = 2;
    static final List<String> TAGS = List.of("字幕");
    static final String NO_TRANSCRIBER = "没有可用的听写模型（能带时间轴的，如 OpenAI 的 whisper-1）：字幕任务要靠它把音频转成文字。"
            + "请管理员在「模型」里添加公司的 OpenAI Key（OpenAI Codex 那一组），模型检查通过后再建这个任务";
    private static final Set<String> AUDIO = Set.of("mp3", "m4a", "wav", "flac", "ogg", "oga", "webm", "mp4", "mpeg", "mpga",
            "mov", "aac", "opus", "mkv");
    private static final Pattern FILE_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int PROMPT_TERMS_CHARS = 800, TERM_TEXT_CHARS = 12000;

    private final ModelCapabilities capabilities;
    private final SpeechToText speech;
    private final TranscriptAligner aligner;
    private final GlossaryService glossary;

    public SubtitleHarness(ModelCapabilities capabilities, SpeechToText speech, TranscriptAligner aligner, GlossaryService glossary) {
        this.capabilities = capabilities;
        this.speech = speech;
        this.aligner = aligner;
        this.glossary = glossary;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public HarnessState start(Task task, String delivery) {
        HarnessState s = new HarnessState();
        s.harness = KIND;
        s.steps = new ArrayList<>(List.of(new HarnessState.Step(MATERIAL, "素材"), new HarnessState.Step(TERMS, "术语"),
                new HarnessState.Step(TRANSCRIBE, "听写"), new HarnessState.Step(ALIGN, "对齐投票"),
                new HarnessState.Step(CALIBRATE, "校准"), new HarnessState.Step(CHECK, "检查"),
                new HarnessState.Step(DELIVER, "交付")));
        s.roundLimit = RETURNS + 1;
        return s;
    }

    /** No speech model with timings: refused at creation, so the admin is asked before anything runs. */
    @Override
    public void precheck(String repo, String branch, String delivery, String owner, HarnessHost host) {
        if (capabilities.transcribers().stream().noneMatch(SpeechModel::timed)) {
            throw new IllegalArgumentException(NO_TRANSCRIBER);
        }
    }

    /** The models this run would use, for the agent's confirm table. */
    public String models() {
        List<SpeechModel> speechModels = capabilities.transcribers();
        List<ModelCapabilities.TextModel> calibrators = capabilities.calibrators();
        StringBuilder sb = new StringBuilder();
        if (speechModels.stream().noneMatch(SpeechModel::timed)) {
            sb.append("❌ ").append(NO_TRANSCRIBER).append("\n");
        }
        sb.append("听写模型 ").append(speechModels.size()).append(" 个（全部并行听写，带时间轴的做骨架）：")
                .append(speechModels.isEmpty() ? "无" : String.join("、", speechModels.stream()
                        .map(m -> m.model() + (m.timed() ? "（时间轴）" : "")).toList())).append("\n");
        sb.append("校准模型 ").append(calibrators.size()).append(" 个（每家厂商最强的一个，上限在系统设置）：")
                .append(calibrators.isEmpty() ? "无（分歧只按听写多数决定，其余列为疑点）"
                        : String.join("、", calibrators.stream().map(ModelCapabilities.TextModel::model).toList())).append("\n");
        if (speechModels.size() == 1) {
            sb.append("只有一个听写模型：没有交叉比对，每一条都交给校准模型看，拿不准的列为疑点。\n");
        }
        return sb.toString();
    }

    @Override
    public void drive(HarnessRun run) {
        HarnessState s = run.state();
        if (s.dir == null) {
            s.dir = run.host().workRoot().resolve(KIND).resolve(run.task().id()).toString();
            run.save();
        }
        run.workIn(s.dir);
        String reply = run.reply();
        while (!run.settled()) {
            HarnessState.Step step = s.current();
            if (step == null) {
                run.finish(output(run.task(), s, SubtitleRecord.read(dir(s))));
                return;
            }
            if (run.cancelled()) {
                run.stopped(step);
                return;
            }
            String answer = reply != null && step.key.equals(s.waitingOn) ? reply : null;
            reply = null;
            try {
                switch (step.key) {
                    case MATERIAL -> material(run, step);
                    case TERMS -> terms(run, step);
                    case TRANSCRIBE -> transcribe(run, step);
                    case ALIGN -> align(run, step);
                    case CALIBRATE -> calibrate(run, step);
                    case CHECK -> check(run, step, answer);
                    case DELIVER -> deliver(run, step);
                    default -> throw new IllegalStateException("Unknown step " + step.key);
                }
            } catch (RuntimeException e) {
                log.warn("task {}: subtitle step {} failed", run.task().id(), step.key, e);
                run.fail(step, step.name + "这一步出错：" + e.getMessage());
            }
        }
    }

    // --- steps ---

    private void material(HarnessRun run, HarnessState.Step step) {
        run.begin(step);
        Path dir = dir(run.state());
        SubtitleRecord r = SubtitleRecord.read(dir);
        Task task = run.task();
        List<String> problems = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = FILE_ID.matcher(task.brief() == null ? "" : task.brief());
        while (m.find()) {
            ids.add(m.group());
        }
        for (String id : ids) {
            Path got;
            try {
                got = run.host().fetch(task, id, dir);
            } catch (RuntimeException e) {
                problems.add(id + "：" + e.getMessage());
                continue;
            }
            String ext = extension(got);
            String rel = dir.relativize(got).toString();
            if (AUDIO.contains(ext)) {
                if (r.audio == null) {
                    r.audio = rel;
                    r.audioName = got.getFileName().toString();
                } else {
                    run.note("另有音频 " + got.getFileName() + "：一个字幕任务只处理一段音频，这一段没用上");
                }
            } else if (DocumentText.readable(got)) {
                r.references.add(rel);
                try {
                    for (DocumentText.Page p : DocumentText.pages(got)) {
                        if (!p.text().isBlank()) {
                            r.slides.add(new SubtitleRecord.Slide(got.getFileName().toString(), p.number(), p.text()));
                        }
                    }
                } catch (IOException e) {
                    problems.add(got.getFileName() + " 读不出文字：" + e.getMessage());
                }
            } else {
                run.note("资料 " + got.getFileName() + " 读不了文字（支持 pptx、pdf、txt、md），没有用上");
            }
        }
        problems.forEach(p -> run.note("✕ " + p));
        if (r.audio == null) {
            run.fail(step, "任务说明里没有找到音频文件（Files 里的文件 id）" + (problems.isEmpty() ? "" : "：" + String.join("；", problems)));
            return;
        }
        r.write(dir);
        run.done(step, r.audioName + (r.references.isEmpty() ? " · 没有参考资料" : " + " + r.references.size() + " 份资料（"
                + r.slides.size() + " 页）"));
    }

    private void terms(HarnessRun run, HarnessState.Step step) {
        run.begin(step);
        Path dir = dir(run.state());
        SubtitleRecord r = SubtitleRecord.read(dir);
        Task task = run.task();
        Map<String, GlossaryService.Found> found = new LinkedHashMap<>();
        SlideMatcher.terms(r.slides).forEach((t, slide) -> found.put(TextTokens.key(t),
                new GlossaryService.Found(t, t.equals(t.toUpperCase(Locale.ROOT)) ? "abbr" : "term", ref(slide))));
        List<ModelCapabilities.TextModel> text = capabilities.textModels();
        if (!r.slides.isEmpty() && !text.isEmpty()) {
            ModelCapabilities.TextModel cheapest = text.get(0);
            run.note("从资料里提取术语（" + cheapest.model() + "）");
            HarnessHost.Turn turn = run.turnOn(new HarnessHost.Model(cheapest.model(), cheapest.cli()),
                    termsPrompt(r.slides), UUID.randomUUID().toString(), TERMS);
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            JsonNode root = Calibration.json(turn.text());
            JsonNode list = root == null ? null : root.isArray() ? root : root.path("terms");
            if (list != null) {
                for (JsonNode t : list) {
                    String term = t.path("term").asText("").strip();
                    if (!term.isEmpty()) {
                        found.putIfAbsent(TextTokens.key(term), new GlossaryService.Found(term,
                                blank(t.path("category").asText(null)), ref(pageWith(r.slides, term))));
                    }
                }
            } else {
                run.note("没读懂术语提取的结果，只用资料里的英文术语");
            }
        }
        String project = run.host().projectOf(task).orElse(null);
        r.termsFound = found.size();
        r.termsAdded = glossary.addFound(project, new ArrayList<>(found.values()), task.createdBy());
        r.terms = glossary.merged(project).stream().map(t -> new SubtitleRecord.Term(t.term(), t.aliases())).toList();
        r.write(dir);
        run.done(step, "资料里 " + r.termsFound + " 个 · 新加入" + (project == null ? "公司" : "项目") + "术语表 "
                + r.termsAdded + " 个 · 共 " + r.terms.size() + " 个");
    }

    private void transcribe(HarnessRun run, HarnessState.Step step) {
        run.begin(step);
        Path dir = dir(run.state());
        SubtitleRecord r = SubtitleRecord.read(dir);
        Task task = run.task();
        List<SpeechModel> models = capabilities.transcribers();
        if (models.stream().noneMatch(SpeechModel::timed)) {
            run.fail(step, NO_TRANSCRIBER);
            return;
        }
        String prompt = speechPrompt(r.termList());
        Path audio = dir.resolve(r.audio);
        String company = TenantContext.require();
        UsageContext paying = new UsageContext(task.createdBy(), UsageContext.SOURCE_TRANSCRIBE, null, task.id(), null, "codex");
        run.note("同时听写：" + String.join("、", models.stream().map(SpeechModel::model).toList()));
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(models.size(), 4));
        List<Transcript> got = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            List<Future<Transcript>> jobs = models.stream().map(m -> pool.submit(() ->
                    TenantContext.callChecked(company, () -> speech.transcribe(m, audio, prompt, null, paying)))).toList();
            for (int i = 0; i < models.size(); i++) {
                try {
                    Transcript t = jobs.get(i).get();
                    got.add(t);
                    run.note("✓ " + t.model() + "：" + t.cues().size() + " 段" + (t.timed() ? "（时间轴）" : "")
                            + String.format(Locale.ROOT, " · $%.3f", t.usd()));
                } catch (java.util.concurrent.ExecutionException e) {
                    String why = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                    failed.add(models.get(i).model() + "：" + why);
                    run.note("✕ " + models.get(i).model() + " 听写失败：" + why);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.stopped(step);
            return;
        } finally {
            pool.shutdownNow();
        }
        double usd = got.stream().mapToDouble(Transcript::usd).sum();
        run.host().spent(task, BigDecimal.valueOf(usd));
        if (run.cancelled()) {
            run.stopped(step);
            return;
        }
        int skeleton = -1;
        for (int i = 0; i < got.size(); i++) {
            if (got.get(i).timed()) {
                skeleton = i;
                break;
            }
        }
        if (skeleton < 0) {
            run.fail(step, "带时间轴的听写没有成功：" + String.join("；", failed));
            return;
        }
        r.transcripts = got;
        r.failed = failed;
        r.skeleton = skeleton;
        r.write(dir);
        run.done(step, got.size() + " 份" + (failed.isEmpty() ? "" : "（" + failed.size() + " 个失败）")
                + String.format(Locale.ROOT, " · $%.2f", usd));
    }

    private void align(HarnessRun run, HarnessState.Step step) {
        run.begin(step);
        Path dir = dir(run.state());
        SubtitleRecord r = SubtitleRecord.read(dir);
        r.models = r.transcripts.stream().map(Transcript::model).toList();
        if (r.transcripts.size() == 1) {
            // Nothing to compare against: every cue is in question.
            r.single = true;
            List<TranscriptAligner.AlignedCue> cues = new ArrayList<>();
            List<Transcript.Cue> in = r.transcripts.get(0).cues();
            for (int i = 0; i < in.size(); i++) {
                String text = TextTokens.join(TextTokens.of(in.get(i).text()));
                cues.add(new TranscriptAligner.AlignedCue(i, in.get(i).start(), in.get(i).end(), text.isEmpty() ? List.of()
                        : List.of(new TranscriptAligner.Span("C" + (i + 1), text, List.of(text)))));
            }
            r.cues = cues;
            r.write(dir);
            run.done(step, "只有一份听写，" + cues.size() + " 段全部交给校准");
            return;
        }
        TranscriptAligner.Alignment a = aligner.align(r.transcripts, r.skeleton);
        r.cues = a.cues();
        r.write(dir);
        run.done(step, "以 " + r.models.get(r.skeleton) + " 为骨架 · " + a.agreedCues() + "/" + a.cues().size()
                + " 段一致 · 分歧 " + a.disputes() + " 处");
    }

    private void calibrate(HarnessRun run, HarnessState.Step step) {
        run.begin(step);
        Path dir = dir(run.state());
        SubtitleRecord r = SubtitleRecord.read(dir);
        List<Calibration.Ask> asks = Calibration.asks(r);
        if (asks.isEmpty()) {
            r.write(dir);
            run.done(step, "各份听写完全一致，没有要校准的");
            return;
        }
        List<ModelCapabilities.TextModel> calibrators = capabilities.calibrators();
        r.calibrators = calibrators.stream().map(ModelCapabilities.TextModel::model).toList();
        Map<String, Map<String, String>> answers = new LinkedHashMap<>();
        for (ModelCapabilities.TextModel model : calibrators) {
            int got = 0;
            for (int from = 0; from < asks.size(); from += Calibration.BATCH) {
                List<Calibration.Ask> batch = asks.subList(from, Math.min(asks.size(), from + Calibration.BATCH));
                HarnessHost.Turn turn = run.turnOn(new HarnessHost.Model(model.model(), model.cli()),
                        Calibration.prompt(batch, r), UUID.randomUUID().toString(), CALIBRATE + " · " + model.model());
                if (turn.cancelled()) {
                    run.stopped(step);
                    return;
                }
                Set<String> ids = new LinkedHashSet<>(batch.stream().map(a -> a.span().id()).toList());
                Map<String, String> fixes = Calibration.parse(turn.text(), ids);
                fixes.forEach((id, text) -> answers.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(model.model(), text));
                got += fixes.size();
            }
            run.note(model.model() + "：给出 " + got + "/" + asks.size() + " 处的写法");
        }
        Map<String, SubtitleRecord.Decision> decisions = new LinkedHashMap<>();
        for (Calibration.Ask a : asks) {
            decisions.put(a.span().id(), Calibration.decide(a, r.models, r.skeleton,
                    answers.getOrDefault(a.span().id(), Map.of()), calibrators.size()));
        }
        r.decisions = decisions;
        r.write(dir);
        long adopted = decisions.values().stream().filter(SubtitleRecord.Decision::adopted).count();
        run.done(step, (calibrators.isEmpty() ? "没有文字模型 · " : calibrators.size() + " 个模型 · ")
                + "采用 " + adopted + " 处 · 疑点 " + (decisions.size() - adopted) + " 处");
    }

    private void check(HarnessRun run, HarnessState.Step step, String answer) {
        HarnessState s = run.state();
        Path dir = dir(s);
        SubtitleRecord r = SubtitleRecord.read(dir);
        if (answer != null) {
            if (GeneralPrompts.approves(answer)) {
                run.note("你确认照现在的样子交付");
                run.done(step, "你确认交付");
                return;
            }
            s.hint = answer;
            s.roundLimit = s.round + RETURNS;
        }
        while (true) {
            run.begin(step, step.name + (s.round > 1 ? " · 第 " + s.round + " 轮" : ""));
            List<SubtitleChecks.Cue> cues = SubtitleChecks.tidy(r.decided(), r.termList(), r.seconds());
            double[] speechAt = r.speech();
            List<HarnessState.PlatformCheck> checks = SubtitleChecks.check(cues, speechAt[0], speechAt[1], r.seconds(), r.termList());
            checks.forEach(p -> run.note((p.passed() ? "✓ " : "✕ ") + p.name() + "：" + p.detail()));
            List<String> issues = checks.stream().filter(p -> !p.passed()).map(p -> p.name() + "：" + p.detail()).toList();
            List<HarnessState.CheckRound> rounds = new ArrayList<>(s.checks);
            rounds.add(new HarnessState.CheckRound(s.round, Instant.now(), issues.isEmpty(), checks, null, null, issues));
            s.checks = rounds;
            run.save();
            if (issues.isEmpty()) {
                run.done(step, "第 " + s.round + " 轮通过 · " + cues.size() + " 条");
                return;
            }
            List<Integer> broken = SubtitleChecks.unbroken(cues).stream().map(i -> cues.get(i).origin()).distinct().toList();
            ModelCapabilities.TextModel fixer = capabilities.calibrators().stream().findFirst().orElse(null);
            if (s.round >= s.roundLimit || broken.isEmpty() || fixer == null) {
                if (run.host().onItsOwn(run.task())) {
                    run.done(step, "未通过，照现状交付");
                } else {
                    run.waitFor(step, stuck(s, issues, broken.isEmpty() || fixer == null));
                }
                return;
            }
            run.tell("字幕检查第 " + s.round + " 轮没通过（" + String.join("；", issues.stream().limit(3).toList())
                    + "），交给 " + fixer.model() + " 重新断句");
            HarnessHost.Turn turn = run.turnOn(new HarnessHost.Model(fixer.model(), fixer.cli()), rebreakPrompt(r, broken, s.hint),
                    UUID.randomUUID().toString(), CHECK + " · 重新断句");
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            int kept = takeBreaks(r, broken, turn.text());
            run.note("重新断句：采用 " + kept + "/" + broken.size() + " 条（文字有变动的不采用）");
            s.hint = null;
            s.round++;
            r.write(dir);
            run.save();
        }
    }

    private void deliver(HarnessRun run, HarnessState.Step step) {
        run.begin(step);
        Path dir = dir(run.state());
        SubtitleRecord r = SubtitleRecord.read(dir);
        publish(run.task(), run.host(), r, dir);
        r.write(dir);
        run.done(step, "srt + 疑点清单（" + doubts(r).size() + " 处）");
    }

    // --- after delivery: a person fixes a doubt ---

    /**
     * {@code text} replaces what the subtitle has at span {@code id}: the files in Files are rewritten, and the change is
     * learnt into the glossary (the project's, else the company's). Returns the term it learnt, if any.
     */
    public Optional<String> fix(Task task, HarnessState s, HarnessHost host, String id, String text, String user) {
        if (s.dir == null) {
            throw new IllegalArgumentException("这个字幕任务还没有结果");
        }
        Path dir = Path.of(s.dir);
        SubtitleRecord r = SubtitleRecord.read(dir);
        SubtitleRecord.Decision d = r.decisions.get(id);
        if (d == null) {
            throw new IllegalArgumentException("没有这一处：" + id);
        }
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        String before = d.shown();
        r.decisions.put(id, d.fixedBy(clean));
        r.breaks.remove(d.cue()); // its re-breaking held the old words
        Optional<String> learnt = glossary.learn(host.projectOf(task).orElse(null), before, clean, user)
                .map(com.superagent.core.glossary.GlossaryTerm::term);
        if (r.srtFileId != null) {
            publish(task, host, r, dir);
        }
        r.write(dir);
        return learnt;
    }

    /** The view the task page shows; null before the flow has a directory. */
    public SubtitleView view(HarnessState s, Task task, HarnessHost host) {
        if (s.dir == null) {
            return null;
        }
        SubtitleRecord r = SubtitleRecord.read(Path.of(s.dir));
        List<SubtitleChecks.Cue> cues = SubtitleChecks.tidy(r.decided(), r.termList(), r.seconds());
        return new SubtitleView(r, cues, glossary.merged(host.projectOf(task).orElse(null)));
    }

    /** What the page gets: the record, the subtitles as they stand, the glossary as it stands. */
    public record SubtitleView(SubtitleRecord record, List<SubtitleChecks.Cue> cues,
                               List<com.superagent.core.glossary.GlossaryTerm> terms) {
    }

    // ---

    private static void publish(Task task, HarnessHost host, SubtitleRecord r, Path dir) {
        List<SubtitleChecks.Cue> cues = SubtitleChecks.tidy(r.decided(), r.termList(), r.seconds());
        String stem = stem(r.audioName);
        try {
            Path out = dir.resolve("out");
            Files.createDirectories(out);
            Path srt = Files.writeString(out.resolve(stem + ".srt"), SubtitleChecks.srt(cues), StandardCharsets.UTF_8);
            Path list = Files.writeString(out.resolve(stem + "-疑点清单.md"), doubtList(r), StandardCharsets.UTF_8);
            r.srtFileId = host.deliver(task, srt, srt.getFileName().toString(), r.srtFileId, TAGS);
            r.doubtsFileId = host.deliver(task, list, list.getFileName().toString(), r.doubtsFileId, TAGS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<SubtitleRecord.Decision> doubts(SubtitleRecord r) {
        return r.decisions.values().stream().filter(SubtitleRecord.Decision::doubt).toList();
    }

    static String doubtList(SubtitleRecord r) {
        List<SubtitleRecord.Decision> doubts = doubts(r);
        StringBuilder sb = new StringBuilder("# ").append(stem(r.audioName)).append(" · 疑点清单\n\n");
        sb.append("听写模型：").append(String.join("、", r.models)).append("（骨架 ").append(r.models.isEmpty() ? "-"
                : r.models.get(r.skeleton)).append("）  \n校准模型：")
                .append(r.calibrators.isEmpty() ? "无" : String.join("、", r.calibrators)).append("  \n");
        long adopted = r.decisions.values().stream().filter(SubtitleRecord.Decision::adopted).count();
        sb.append("分歧 ").append(r.decisions.size()).append(" 处：多数一致自动采用 ").append(adopted).append(" 处，")
                .append("疑点 ").append(doubts.size()).append(" 处（下表，字幕里暂用骨架的写法）。\n\n");
        if (doubts.isEmpty()) {
            return sb.append("没有疑点。\n").toString();
        }
        sb.append("| 时间码 | 各模型原文 | 校准 | 采用 | 理由 |\n|---|---|---|---|---|\n");
        for (SubtitleRecord.Decision d : doubts) {
            List<String> heard = new ArrayList<>();
            for (int v = 0; v < d.heard().size(); v++) {
                heard.add(r.models.get(v) + "「" + d.heard().get(v) + "」");
            }
            List<String> cal = d.calibrated().entrySet().stream().map(e -> e.getKey() + "「" + e.getValue() + "」").toList();
            sb.append("| ").append(SubtitleChecks.time(d.start())).append(" → ").append(SubtitleChecks.time(d.end()))
                    .append(" | ").append(cell(String.join("<br>", heard))).append(" | ").append(cell(cal.isEmpty() ? "-" : String.join("<br>", cal)))
                    .append(" | ").append(cell(d.shown())).append(d.userText() != null ? "（已人工修改）" : "")
                    .append(" | ").append(cell(d.reason())).append(" |\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String output(Task task, HarnessState s, SubtitleRecord r) {
        List<SubtitleRecord.Decision> doubts = doubts(r);
        StringBuilder sb = new StringBuilder("## ").append(task.title()).append("\n\n");
        sb.append("字幕和疑点清单已存进「文件」：").append(stem(r.audioName)).append(".srt、").append(stem(r.audioName))
                .append("-疑点清单.md。\n\n");
        sb.append("- 听写：").append(String.join("、", r.models)).append(r.single ? "（只有一份，未交叉比对）" : "").append('\n');
        if (!r.failed.isEmpty()) {
            sb.append("- 听写失败：").append(String.join("；", r.failed)).append('\n');
        }
        sb.append("- 校准：").append(r.calibrators.isEmpty() ? "无文字模型" : String.join("、", r.calibrators)).append('\n');
        long adopted = r.decisions.values().stream().filter(SubtitleRecord.Decision::adopted).count();
        sb.append("- 分歧 ").append(r.decisions.size()).append(" 处：自动采用 ").append(adopted).append(" 处，疑点 ")
                .append(doubts.size()).append(" 处\n");
        sb.append("- 术语：资料里 ").append(r.termsFound).append(" 个，新加入术语表 ").append(r.termsAdded).append(" 个\n");
        HarnessState.CheckRound last = s.checks.isEmpty() ? null : s.checks.get(s.checks.size() - 1);
        if (last != null) {
            sb.append("- 检查：").append(last.passed() ? "第 " + last.round() + " 轮通过" : "未通过（" + String.join("；", last.issues()) + "）")
                    .append('\n');
        }
        if (!doubts.isEmpty()) {
            sb.append("\n在任务详情的「疑点」页可以直接改，改动会记进术语表，下次识别更准。\n");
        }
        return sb.toString();
    }

    private static String stuck(HarnessState s, List<String> issues, boolean noFix) {
        StringBuilder sb = new StringBuilder("字幕检查了 ").append(s.round).append(" 轮还有问题：\n");
        issues.forEach(i -> sb.append("- ").append(i).append('\n'));
        if (noFix) {
            sb.append("\n这些问题模型改不了（时间轴或覆盖时长来自听写）。");
        }
        return sb.append("\n可以回复修改意见再改一轮，或者照现在的样子交付。\n\n👉 下一步：回复「确认交付」或「停止任务」").toString();
    }

    private static String rebreakPrompt(SubtitleRecord r, List<Integer> broken, String hint) {
        Map<Integer, SubtitleChecks.Cue> whole = whole(r);
        StringBuilder sb = new StringBuilder("""
                下面几条字幕太长，请把每条拆成几段，每段在屏幕上最多两行、每行约 20 个汉字（英文字母算半个），每段读出来不超过 7 秒。
                只能断句：文字必须和原文一模一样（只可改空格），不能增删改任何字词，不翻译。
                只输出 JSON：{"cues":[{"index":编号,"parts":["第一段","第二段"]}]}
                """);
        if (hint != null) {
            sb.append("\n用户的要求：").append(hint).append('\n');
        }
        for (int i : broken) {
            SubtitleChecks.Cue c = whole.get(i);
            sb.append("\n").append(i).append("（").append(String.format(Locale.ROOT, "%.1f", c.seconds())).append(" 秒）：")
                    .append(c.text()).append('\n');
        }
        return sb.toString();
    }

    /** A model's re-breaking, kept for the cues where its parts hold exactly the cue's words. */
    static int takeBreaks(SubtitleRecord r, List<Integer> asked, String reply) {
        JsonNode root = Calibration.json(reply);
        if (root == null) {
            return 0;
        }
        Map<Integer, SubtitleChecks.Cue> whole = whole(r);
        int kept = 0;
        for (JsonNode c : root.isArray() ? root : root.path("cues")) {
            int i = c.path("index").asInt(-1);
            if (!asked.contains(i) || !c.path("parts").isArray()) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            c.path("parts").forEach(p -> parts.add(p.asText("").strip()));
            parts.removeIf(String::isEmpty);
            if (!parts.isEmpty() && TextTokens.key(String.join(" ", parts)).equals(TextTokens.key(whole.get(i).text()))) {
                r.breaks.put(i, parts);
                kept++;
            }
        }
        return kept;
    }

    /** Each aligned cue's whole decided text (its parts put back together). */
    private static Map<Integer, SubtitleChecks.Cue> whole(SubtitleRecord r) {
        Map<Integer, SubtitleChecks.Cue> out = new LinkedHashMap<>();
        for (SubtitleChecks.Cue c : r.decided()) {
            out.merge(c.origin(), c, (a, b) -> new SubtitleChecks.Cue(a.origin(), a.start(), b.end(), TextTokens.concat(a.text(), b.text())));
        }
        return out;
    }

    private static String termsPrompt(List<SubtitleRecord.Slide> slides) {
        StringBuilder text = new StringBuilder();
        for (SubtitleRecord.Slide s : slides) {
            if (text.length() > TERM_TEXT_CHARS) {
                break;
            }
            text.append("\n[").append(s.file()).append(" 第 ").append(s.page()).append(" 页]\n").append(s.text()).append('\n');
        }
        return """
                下面是一段讲课或会议音频配套的资料文字。找出里面的专有名词、英文术语、缩写、人名（中文人名也算）、产品和公司名，
                它们是语音识别最容易听错的词。原样照资料的写法给出，不翻译，不要普通词。
                每个给出类别：name（专名）、term（术语）、abbr（缩写）、person（人名）。
                只输出 JSON：{"terms":[{"term":"LoRA","category":"abbr"}]}
                """ + text;
    }

    /** The terms as a speech model's prompt: comma-separated, as many as fit. */
    static String speechPrompt(List<String> terms) {
        StringBuilder sb = new StringBuilder();
        for (String t : terms) {
            if (sb.length() + t.length() + 2 > PROMPT_TERMS_CHARS) {
                break;
            }
            sb.append(sb.isEmpty() ? "" : "、").append(t);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static SubtitleRecord.Slide pageWith(List<SubtitleRecord.Slide> slides, String term) {
        String t = term.toLowerCase(Locale.ROOT);
        return slides.stream().filter(s -> s.text().toLowerCase(Locale.ROOT).contains(t)).findFirst()
                .orElse(slides.isEmpty() ? null : slides.get(0));
    }

    private static String ref(SubtitleRecord.Slide s) {
        return s == null ? null : s.file() + " 第 " + s.page() + " 页";
    }

    private static Path dir(HarnessState s) {
        return Path.of(s.dir);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stem(String name) {
        if (name == null) {
            return "字幕";
        }
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
