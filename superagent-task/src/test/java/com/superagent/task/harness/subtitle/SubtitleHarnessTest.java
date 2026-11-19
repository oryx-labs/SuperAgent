package com.superagent.task.harness.subtitle;

import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.glossary.GlossaryStore;
import com.superagent.core.glossary.GlossaryTerm;
import com.superagent.core.media.ModelCapabilities;
import com.superagent.core.media.SpeechModel;
import com.superagent.core.media.SpeechToText;
import com.superagent.core.media.Transcript;
import com.superagent.core.media.TranscriptAligner;
import com.superagent.core.media.TranscriptAligner.Span;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.tenant.TenantContext;
import com.superagent.glossary.GlossaryService;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The subtitle flow with scripted models: every speech model transcribes, the platform votes, text models settle only
 * what is in dispute (a writing stands on a majority), the platform checks the norms, and a person's fix is learnt.
 */
class SubtitleHarnessTest {

    private static final String AUDIO_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DECK_ID = "22222222-2222-2222-2222-222222222222";
    private static final ModelCapabilities.TextModel OPUS = new ModelCapabilities.TextModel("claude-opus-5-5", "claude", "anthropic");
    private static final ModelCapabilities.TextModel GPT = new ModelCapabilities.TextModel("gpt-5.5", "codex", "openai");
    private static final ModelCapabilities.TextModel GLM = new ModelCapabilities.TextModel("glm-4.6", "glm", "zhipu");

    @TempDir
    Path tmp;

    private final Caps caps = new Caps();
    private final Map<String, Transcript> heard = new LinkedHashMap<>();
    private final List<String> transcribed = new ArrayList<>();
    private TranscriptAligner.Alignment alignment;
    private final MemoryGlossary terms = new MemoryGlossary();
    private SubtitleHarness harness;

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        SpeechToText speech = (m, file, prompt, language, paying) -> {
            synchronized (transcribed) {
                transcribed.add(m.model() + " | " + prompt);
            }
            Transcript t = heard.get(m.model());
            if (t == null) {
                throw new IllegalStateException("HTTP 500");
            }
            return t;
        };
        TranscriptAligner aligner = (versions, skeleton) -> alignment;
        harness = new SubtitleHarness(caps, speech, aligner, new GlossaryService(terms));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- the flow ---

    @Test
    void everySpeechModelTranscribesAndOnlyAMajorityOfCalibratorsChangesADisputedSpan() throws Exception {
        caps.speech = List.of(new SpeechModel("openai", "whisper-1", true), new SpeechModel("openai", "gpt-4o-transcribe", false),
                new SpeechModel("openai", "gpt-4o-mini-transcribe", false));
        caps.calibrators = List.of(OPUS, GPT, GLM);
        heard.put("whisper-1", timed("今天我们讲 transformer 里的 attention", "我们用 Laura 微调"));
        heard.put("gpt-4o-transcribe", untimed("gpt-4o-transcribe"));
        heard.put("gpt-4o-mini-transcribe", untimed("gpt-4o-mini-transcribe"));
        alignment = new TranscriptAligner.Alignment(List.of("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe"), List.of(
                new TranscriptAligner.AlignedCue(0, 0, 3, List.of(Span.agreed("今天我们讲"),
                        new Span("D1", "transformer", List.of("transformer", "Transformer", "穿死佛么")), Span.agreed("里的 attention"))),
                new TranscriptAligner.AlignedCue(1, 3, 6, List.of(Span.agreed("我们用"),
                        new Span("D2", "Laura", List.of("Laura", "LoRA", "罗拉")), Span.agreed("微调")))));
        Host host = new Host();
        // All three agree on D1; on D2 each writes something else. One also tries to rewrite text no one disputes.
        host.calibrate = model -> switch (model) {
            case "claude-opus-5-5" -> "{\"fixes\":[{\"id\":\"D1\",\"text\":\"Transformer\"},{\"id\":\"D2\",\"text\":\"LoRA\"}]}";
            case "gpt-5.5" -> "好的：{\"fixes\":[{\"id\":\"D1\",\"text\":\"transformer\"},{\"id\":\"D2\",\"text\":\"Laura\"},"
                    + "{\"id\":\"D9\",\"text\":\"今天我们不讲\"}]}";
            default -> "[{\"id\":\"D1\",\"text\":\"Transformer\"},{\"id\":\"D2\",\"text\":\"LORA 2\"}]";
        };

        HarnessState s = drive(host, null);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(s.steps).allMatch(st -> st.status == HarnessState.Status.DONE);
        // Side by side, with the deck's terms as the prompt (the deck's terms went into the project's glossary).
        assertThat(transcribed).hasSize(3).allMatch(p -> p.contains("LoRA") && p.contains("GPT-4o"));
        assertThat(terms.list("p1")).extracting(GlossaryTerm::term).contains("LoRA", "GPT-4o", "RAG");
        assertThat(host.spent).isEqualByComparingTo("0.03");
        // Only the disputed spans were asked about, each calibrator in a conversation of its own.
        assertThat(host.prompts).hasSize(4); // terms + three calibrators
        assertThat(host.prompts.get(1)).contains("### D1", "### D2", "穿死佛么", "前文：今天我们讲", "第02课.pptx 第 2 页")
                .doesNotContain("### 今天");
        assertThat(host.conversations).doesNotHaveDuplicates();

        SubtitleRecord r = SubtitleRecord.read(Path.of(s.dir));
        assertThat(r.decisions.get("D1")).satisfies(d -> {
            assertThat(d.adopted()).isTrue();
            assertThat(d.text()).isEqualTo("Transformer");
            assertThat(d.reason()).contains("3/3");
        });
        assertThat(r.decisions.get("D2")).satisfies(d -> {
            assertThat(d.doubt()).isTrue();
            assertThat(d.text()).isEqualTo("Laura"); // the skeleton's, until a person decides
            assertThat(d.calibrated()).containsOnlyKeys("claude-opus-5-5", "gpt-5.5", "glm-4.6");
        });
        String srt = host.delivered.get("第02课.srt");
        assertThat(srt).isEqualTo("1\n00:00:00,000 --> 00:00:03,000\n今天我们讲 Transformer 里的 attention\n\n"
                + "2\n00:00:03,000 --> 00:00:06,000\n我们用 Laura 微调\n\n");
        assertThat(host.delivered.get("第02课-疑点清单.md")).contains("00:00:03,000", "gpt-4o-transcribe「LoRA」", "没有过半")
                .doesNotContain("穿死佛么");
        assertThat(host.output).contains("第02课.srt", "疑点 1 处");

        // A person fixes the doubt: the subtitles are rewritten in place and the glossary learns the mishearing.
        Optional<String> learnt = harness.fix(task(), s, host, "D2", "LoRA", "ada");
        assertThat(learnt).contains("LoRA");
        assertThat(host.delivered.get("第02课.srt")).contains("我们用 LoRA 微调");
        assertThat(host.replaced).contains("srt-1");
        assertThat(new GlossaryService(terms).merged("p1")).filteredOn(t -> t.term().equals("LoRA")).singleElement()
                .satisfies(t -> assertThat(t.aliases()).contains("Laura"));
    }

    @Test
    void oneSpeechModelPutsEveryCueBeforeTheCalibratorsAndNoneIsRefusedAtCreation() {
        caps.speech = List.of();
        assertThatThrownBy(() -> harness.precheck(null, null, null, "ada", new Host()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("管理员");
        caps.speech = List.of(new SpeechModel("openai", "gpt-4o-transcribe", false)); // no timings: no skeleton
        assertThatThrownBy(() -> harness.precheck(null, null, null, "ada", new Host())).isInstanceOf(IllegalArgumentException.class);

        caps.speech = List.of(new SpeechModel("openai", "whisper-1", true));
        harness.precheck(null, null, null, "ada", new Host());
        caps.calibrators = List.of(OPUS, GPT);
        heard.put("whisper-1", timed("大家好", "今天讲 rag"));
        Host host = new Host();
        host.calibrate = model -> model.equals("gpt-5.5")
                ? "{\"fixes\":[{\"id\":\"C1\",\"text\":\"大家好\"},{\"id\":\"C2\",\"text\":\"今天讲 RAG\"}]}"
                : "{\"fixes\":[{\"id\":\"C1\",\"text\":\"大家好\"},{\"id\":\"C2\",\"text\":\"今天讲 rack\"}]}";

        HarnessState s = drive(host, null);

        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        SubtitleRecord r = SubtitleRecord.read(Path.of(s.dir));
        assertThat(r.single).isTrue();
        assertThat(r.decisions).containsOnlyKeys("C1", "C2");
        assertThat(r.decisions.get("C1").adopted()).isTrue();
        assertThat(r.decisions.get("C2").doubt()).isTrue();
        assertThat(host.delivered.get("第02课.srt")).contains("今天讲 RAG"); // the glossary's casing, from the deck
        assertThat(harness.models()).contains("听写模型 1 个", "校准模型 2 个", "只有一个听写模型");
    }

    @Test
    void checksThatFailGoBackForReBreakingTwiceThenTheUserDecides() {
        caps.speech = List.of(new SpeechModel("openai", "whisper-1", true));
        caps.calibrators = List.of(OPUS);
        String url = "https://example.com/" + "a".repeat(60);
        heard.put("whisper-1", timed("请看 " + url));
        Host host = new Host();
        host.calibrate = model -> "{\"fixes\":[{\"id\":\"C1\",\"text\":\"请看 " + url + "\"}]}";
        host.rebreak = "{\"cues\":[{\"index\":0,\"parts\":[\"请看\",\"别的链接\"]}]}"; // changes the words: not taken

        HarnessState s = drive(host, null);

        assertThat(host.status).isEqualTo(TaskStatus.NEEDS_CONFIRMATION);
        assertThat(s.checks).hasSize(3).noneMatch(HarnessState.CheckRound::passed);
        assertThat(s.checks.get(0).issues()).anyMatch(i -> i.startsWith("断句"));
        assertThat(host.rebreaks).isEqualTo(2);
        assertThat(host.asks).singleElement().satisfies(a -> assertThat(a).contains("确认交付"));

        drive(host, s, "确认交付");
        assertThat(host.status).isEqualTo(TaskStatus.DONE);
        assertThat(host.delivered).containsKey("第02课.srt");
    }

    // --- the parts on their own ---

    @Test
    void oneCalibratorAloneOnlyPicksAWritingSomeTranscriptHadAndWithoutAnyTheTranscriptsVote() {
        Calibration.Ask ask = new Calibration.Ask(new Span("D1", "Laura", List.of("Laura", "LoRA", "LoRA")), 0, 0, 3, "", "", null);
        List<String> models = List.of("whisper-1", "a", "b");
        assertThat(Calibration.decide(ask, models, 0, Map.of("opus", "lora"), 1).adopted()).isTrue();
        assertThat(Calibration.decide(ask, models, 0, Map.of("opus", "Lora 模型"), 1)).satisfies(d -> {
            assertThat(d.doubt()).isTrue();
            assertThat(d.text()).isEqualTo("Laura");
        });
        assertThat(Calibration.decide(ask, models, 0, Map.of(), 0)).satisfies(d -> {
            assertThat(d.adopted()).isTrue(); // no calibrator: 2 of 3 transcripts
            assertThat(d.text()).isEqualTo("LoRA");
        });
        assertThat(Calibration.decide(ask, models, 0, Map.of("x", "LoRA", "y", "Laura"), 2).doubt()).isTrue(); // 1/2 is no majority
        // A calibrator that gave no answer counts against: 2 of 4 agreeing is no majority, 3 of 4 is.
        assertThat(Calibration.decide(ask, models, 0, Map.of("w", "LoRA", "x", "LoRA"), 4).doubt()).isTrue();
        assertThat(Calibration.decide(ask, models, 0, Map.of("w", "LoRA", "x", "LoRA", "y", "LoRA"), 4).adopted()).isTrue();
        // One of three answering is not "one calibrator alone": it needs two of the three.
        assertThat(Calibration.decide(ask, models, 0, Map.of("w", "LoRA"), 3)).satisfies(d -> {
            assertThat(d.doubt()).isTrue();
            assertThat(d.reason()).contains("2 个没有回答");
        });
        assertThat(Calibration.decide(ask, models, 0, Map.of(), 3).doubt()).isTrue();
        assertThat(Calibration.parse("{\"fixes\":[{\"id\":\"D1\",\"text\":\"a\"},{\"id\":\"D7\",\"text\":\"b\"}]}", java.util.Set.of("D1")))
                .containsOnlyKeys("D1");
    }

    @Test
    void theNormsAreFixedWhereThePlatformCanAndCheckedOtherwise() {
        List<SubtitleChecks.Cue> tidy = SubtitleChecks.tidy(List.of(
                new SubtitleChecks.Cue(0, 0, 10, "今天我们讲lora微调，它是一种参数高效的方法，可以只训练很少的参数就达到接近全量微调的效果"),
                new SubtitleChecks.Cue(1, 9, 11, "   "),
                new SubtitleChecks.Cue(2, 10.5, 12, "谢谢大家")), List.of("LoRA"), 12);
        List<HarnessState.PlatformCheck> checks = SubtitleChecks.check(tidy, 0, 12, 12, List.of("LoRA"));
        assertThat(checks).allMatch(HarnessState.PlatformCheck::passed);
        assertThat(tidy.get(0).text()).startsWith("今天我们讲 LoRA 微调");
        assertThat(tidy).noneMatch(c -> c.text().isBlank()).allMatch(c -> c.seconds() <= SubtitleChecks.MAX_SECONDS);
        assertThat(tidy.get(tidy.size() - 1).start()).isGreaterThanOrEqualTo(tidy.get(tidy.size() - 2).end());

        // Cut off midway: the skeleton heard speech only to minute 10 of an 18:42 recording.
        List<SubtitleChecks.Cue> cut = List.of(new SubtitleChecks.Cue(0, 0.5, 4, "大家好"), new SubtitleChecks.Cue(1, 596, 600, "到这里"));
        assertThat(SubtitleChecks.check(cut, 0.5, 600, 1122, List.of())).filteredOn(c -> c.name().equals("覆盖时长"))
                .singleElement().satisfies(c -> assertThat(c.passed()).isFalse());
        assertThat(SubtitleChecks.check(cut, 0.5, 600, 601.5, List.of())).allMatch(HarnessState.PlatformCheck::passed);

        List<HarnessState.PlatformCheck> bad = SubtitleChecks.check(List.of(new SubtitleChecks.Cue(0, 2, 4, "讲lora"),
                new SubtitleChecks.Cue(1, 3, 5, ""), new SubtitleChecks.Cue(2, 5, 6, "完")), 0, 30, 30, List.of("LoRA"));
        assertThat(bad).filteredOn(c -> !c.passed()).extracting(HarnessState.PlatformCheck::name)
                .containsExactly("时间轴", "覆盖时长", "空字幕条", "中英文空格", "术语大小写");
    }

    // --- fakes ---

    private static Transcript timed(String... cues) {
        List<Transcript.Cue> out = new ArrayList<>();
        for (int i = 0; i < cues.length; i++) {
            out.add(new Transcript.Cue(i * 3, i * 3 + 3, cues[i]));
        }
        return new Transcript("whisper-1", true, out, cues.length * 3, 0.01);
    }

    private static Transcript untimed(String model) {
        return new Transcript(model, false, List.of(new Transcript.Cue(0, 6, "…")), 6, 0.01);
    }

    private HarnessState drive(Host host, HarnessState s) {
        return drive(host, s, null);
    }

    private HarnessState drive(Host host, HarnessState s, String reply) {
        HarnessState state = s != null ? s : harness.start(task(), null);
        host.asksBefore = host.asks.size();
        harness.drive(new HarnessRun(host, task(), state, reply, new Cancellation()));
        return state;
    }

    private static Task task() {
        return new Task("t-1", "第02课字幕", "把第02课录音做成字幕。\n→ 文件 " + AUDIO_ID + "（第02课.mp3）\n→ 文件 " + DECK_ID
                + "（第02课.pptx）", TaskSource.WEB_CHAT, null, null, "subtitle", TaskStatus.RUNNING, 0, BigDecimal.ZERO,
                "m", AgentType.CLAUDE, null, "ada", null, Instant.now(), Instant.now(), null, Instant.now(), null);
    }

    private static final class Caps implements ModelCapabilities {
        List<SpeechModel> speech = List.of();
        List<TextModel> calibrators = List.of();

        @Override public List<SpeechModel> transcribers() { return speech; }
        @Override public List<TextModel> textModels() { return calibrators; }
        @Override public List<TextModel> calibrators() { return calibrators; }
    }

    private static final class MemoryGlossary implements GlossaryStore {
        private final Map<String, GlossaryTerm> rows = new ConcurrentHashMap<>();

        @Override
        public List<GlossaryTerm> list(String projectId) {
            String company = TenantContext.require();
            return rows.entrySet().stream().filter(e -> e.getKey().startsWith(company + "/"))
                    .map(Map.Entry::getValue).filter(t -> java.util.Objects.equals(t.projectId(), projectId)).toList();
        }

        @Override public Optional<GlossaryTerm> find(String id) { return Optional.ofNullable(rows.get(TenantContext.require() + "/" + id)); }
        @Override public void save(GlossaryTerm t) { rows.put(TenantContext.require() + "/" + t.id(), t); }
        @Override public void delete(String id) { rows.remove(TenantContext.require() + "/" + id); }
    }

    private final class Host implements HarnessHost {
        Function<String, String> calibrate = m -> "{}";
        String rebreak = "{}";
        int rebreaks;
        int asksBefore;
        final List<String> prompts = new ArrayList<>();
        final List<String> conversations = new ArrayList<>();
        final List<String> asks = new ArrayList<>();
        final Map<String, String> delivered = new LinkedHashMap<>();
        final List<String> replaced = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        TaskStatus status;
        String output;

        @Override
        public Turn turnOn(Task task, Model model, String prompt, String conversation, String label, Cancellation c) {
            conversations.add(conversation);
            if (label.startsWith(SubtitleHarness.CHECK)) {
                rebreaks++;
                return new Turn(rebreak, BigDecimal.ZERO, false);
            }
            prompts.add(prompt);
            if (label.equals(SubtitleHarness.TERMS)) {
                return new Turn("{\"terms\":[{\"term\":\"RAG\",\"category\":\"abbr\"}]}", BigDecimal.ZERO, false);
            }
            return new Turn(calibrate.apply(model.model()), BigDecimal.ZERO, false);
        }

        @Override
        public Path fetch(Task task, String fileId, Path dir) {
            try {
                Path up = Files.createDirectories(dir.resolve("uploads"));
                if (fileId.equals(AUDIO_ID)) {
                    return Files.write(up.resolve("第02课.mp3"), new byte[]{1});
                }
                Path deck = up.resolve("第02课.pptx");
                try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(deck))) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("ppt/slides/slide2.xml"));
                    zip.write("<a:p><a:t>LoRA 低秩适配 · GPT-4o 与 RAG · 我们用 LoRA 微调</a:t></a:p>".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                return deck;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public String deliver(Task task, Path file, String name, String replaceId, List<String> tags) {
            try {
                delivered.put(name, Files.readString(file));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            if (replaceId != null) {
                replaced.add(replaceId);
            }
            assertThat(tags).containsExactly("字幕");
            return name.endsWith(".srt") ? "srt-1" : "doubts-1";
        }

        @Override public Optional<String> projectOf(Task task) { return Optional.of("p1"); }
        @Override public void spent(Task task, BigDecimal usd) { spent = spent.add(usd); }
        @Override public Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation c) {
            throw new AssertionError("the subtitle flow has no task session");
        }
        @Override public Task workIn(Task task, String dir) { return task; }
        @Override public void progress(Task task, int percent) { }
        @Override public void note(Task task, String text) { }
        @Override public void ask(Task task, String text) { asks.add(text); }
        @Override public void tell(Task task, String text) { }
        @Override public void save(Task task, HarnessState state) { }
        @Override public void settle(Task task, TaskStatus s, int progress, String output, String error) {
            status = s;
            this.output = output;
            if (error != null) {
                throw new AssertionError(error);
            }
        }
        @Override public Optional<String> connectorToken(String connectorId, String owner) { return Optional.empty(); }
        @Override public Optional<String> connectorSite(String connectorId, String owner) { return Optional.empty(); }
        @Override public List<Path> localRoots() { return List.of(); }
        @Override public Path workRoot() { return tmp; }
    }
}
