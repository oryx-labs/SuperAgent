package com.superagent.media;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.error.NeedsUserException;
import com.superagent.core.usage.UsageContext;
import com.superagent.core.usage.UsageRecorder;
import com.superagent.platform.provider.CredentialService;
import com.superagent.platform.provider.FfmpegService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Speech to text with timings, on the company's own OpenAI keys (the Codex pool: the same account, and its failover).
 * The platform makes the call, so the key never reaches an agent's shell. Long or unusual files are cut into mono mp3
 * pieces with ffmpeg first, when the server has it (looked up on each call, so one installed meanwhile is used at
 * once); each piece's timings are shifted back onto the whole.
 */
@Service
public class Transcriber {

    static final String PROVIDER = "codex";
    static final String MODEL = "whisper-1";
    /** OpenAI's transcription models and their list prices per minute; only whisper-1 gives timings. */
    static final Map<String, Double> USD_PER_MINUTE = Map.of(MODEL, 0.006, "gpt-4o-transcribe", 0.006,
            "gpt-4o-mini-transcribe", 0.003);
    /** The API takes 25 MB a file; a little room for the form around it. */
    static final long MAX_BYTES = 24L * 1024 * 1024;
    static final int PIECE_SECONDS = 1200;
    /** A model without timings writes at most ~2000 tokens an answer: shorter pieces, so none is cut off. */
    static final int UNTIMED_PIECE_SECONDS = 240;
    static final Set<String> ACCEPTED = Set.of("flac", "m4a", "mp3", "mp4", "mpeg", "mpga", "oga", "ogg", "wav", "webm");
    private static final int FAILOVERS = 3;

    public record Segment(double start, double end, String text) {
    }

    /** What the API returned for one piece. */
    record Piece(double seconds, List<Segment> segments) {
    }

    record Request(String model, String prompt, String language) {
    }

    /** The call itself, a seam for tests. */
    interface Api {
        Piece transcribe(RunCredential credential, Path file, Request request) throws ApiException;
    }

    static boolean timed(String model) {
        return MODEL.equals(model);
    }

    /** The API refused or couldn't be reached; the message is what decides whether another key could help. */
    static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }

    public record Result(List<Segment> segments, double seconds, double usd) {

        public String srt() {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Segment s : segments) {
                if (s.text().isBlank()) {
                    continue;
                }
                sb.append(++n).append('\n').append(time(s.start())).append(" --> ").append(time(s.end())).append('\n')
                        .append(s.text().strip()).append("\n\n");
            }
            return sb.toString();
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            segments.stream().map(s -> s.text().strip()).filter(t -> !t.isEmpty()).forEach(t -> sb.append(t).append('\n'));
            return sb.toString();
        }

        static String time(double seconds) {
            long ms = Math.round(seconds * 1000);
            return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
        }
    }

    private final CredentialService credentials;
    private final Api api;
    private final Supplier<Ffmpeg> ffmpeg; // gives null: the server has none, so only files the API takes as they are
    private UsageRecorder usage; // null in tests

    @Autowired
    public Transcriber(CredentialService credentials, FfmpegService ffmpeg) {
        this(credentials, new OpenAiTranscriptions(), () -> ffmpeg.find().map(Ffmpeg::new).orElse(null));
    }

    Transcriber(CredentialService credentials, Api api, Ffmpeg ffmpeg) {
        this(credentials, api, () -> ffmpeg);
    }

    private Transcriber(CredentialService credentials, Api api, Supplier<Ffmpeg> ffmpeg) {
        this.credentials = credentials;
        this.api = api;
        this.ffmpeg = ffmpeg;
    }

    /** Whether the server has ffmpeg now: without it a file goes in as it is, at most 24 MB in one of the formats. */
    public boolean converts() {
        return ffmpeg.get() != null;
    }

    @Autowired(required = false)
    void setUsage(UsageRecorder usage) {
        this.usage = usage;
    }

    /**
     * {@code language}: ISO-639-1 ("zh", "en"), or null to detect it. {@code paying}: who the minutes go on. A problem
     * the user must fix (no key, a file it can't take) is a {@link NeedsUserException}.
     */
    public Result transcribe(Path file, String language, UsageContext paying) throws IOException {
        return transcribe(MODEL, file, null, language, paying);
    }

    /** {@code prompt}: terms to spell right (null: none). */
    public Result transcribe(String model, Path file, String prompt, String language, UsageContext paying) throws IOException {
        if (!USD_PER_MINUTE.containsKey(model)) {
            throw new NeedsUserException("不支持的转写模型：" + model);
        }
        Ffmpeg ffmpeg = this.ffmpeg.get();
        String ext = extension(file);
        long size = Files.size(file);
        boolean timed = timed(model);
        Path scratch = null;
        try {
            List<Path> pieces;
            if (!timed && ffmpeg != null) {
                scratch = Files.createTempDirectory("sa-transcribe-");
                pieces = ffmpeg.split(file, scratch, UNTIMED_PIECE_SECONDS);
            } else if (size <= MAX_BYTES && ACCEPTED.contains(ext)) {
                pieces = List.of(file);
            } else if (ffmpeg == null) {
                throw new NeedsUserException((size > MAX_BYTES
                        ? String.format(Locale.ROOT, "这个文件有 %.0f MB，超过转写单次上限 24 MB", size / 1048576.0)
                        : "转写不直接支持 ." + ext + " 格式（支持 " + String.join("、", ACCEPTED.stream().sorted().toList()) + "）")
                        + "，服务器上又没有 ffmpeg，没法自动转码切段。办法：请管理员在「模型」页安装 ffmpeg（装好不用重启）；"
                        + "或者先把它转成单声道 mp3 并压到 24 MB 以内再上传。处理好后回复「继续」");
            } else {
                scratch = Files.createTempDirectory("sa-transcribe-");
                pieces = ffmpeg.split(file, scratch, PIECE_SECONDS);
            }
            int pieceSeconds = timed ? PIECE_SECONDS : UNTIMED_PIECE_SECONDS;
            List<Segment> all = new ArrayList<>();
            double offset = 0;
            double usd = 0;
            for (Path piece : pieces) {
                Piece p = call(piece, new Request(model, prompt, language));
                double seconds = p.seconds() > 0 ? p.seconds() : pieces.size() > 1 ? pieceSeconds : 0;
                for (Segment s : p.segments()) {
                    all.add(new Segment(s.start() + offset, (timed ? s.end() : seconds) + offset, s.text()));
                }
                offset += seconds;
                double cost = seconds / 60 * USD_PER_MINUTE.get(model);
                usd += cost;
                if (usage != null && paying != null) {
                    usage.recordCost(paying, model, cost); // per piece: a later failure doesn't hide what was spent
                }
            }
            return new Result(all, offset, usd);
        } finally {
            if (scratch != null) {
                try (Stream<Path> walk = Files.walk(scratch)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
    }

    /** Whether each OpenAI transcription model answers on the company's key; empty-handed results without one. */
    List<com.superagent.core.media.SpeechEngine.Probe> probe(java.util.function.BiConsumer<RunCredential, String> send) {
        CredentialService.Choice choice;
        try {
            choice = credentials.current(PROVIDER);
        } catch (CredentialService.NoCompanyKeyException e) {
            return USD_PER_MINUTE.keySet().stream().sorted().map(m -> new com.superagent.core.media.SpeechEngine.Probe(m,
                    timed(m), false, "没有 OpenAI Key")).toList();
        }
        RunCredential credential = choice.credential();
        return USD_PER_MINUTE.keySet().stream().sorted().map(m -> {
            try {
                send.accept(credential, m);
                return new com.superagent.core.media.SpeechEngine.Probe(m, timed(m), true, null);
            } catch (RuntimeException e) {
                return new com.superagent.core.media.SpeechEngine.Probe(m, timed(m), false, e.getMessage());
            }
        }).toList();
    }

    private Piece call(Path piece, Request request) {
        CredentialService.Choice choice;
        try {
            choice = credentials.current(PROVIDER);
        } catch (CredentialService.NoCompanyKeyException e) {
            throw new NeedsUserException("语音转写用的是公司的 OpenAI Key（「模型」里 OpenAI Codex 那一组），还没有配置：请管理员先添加，"
                    + "然后回复「继续」");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return api.transcribe(choice.credential(), piece, request);
            } catch (ApiException e) {
                Optional<CredentialService.Choice> next = attempt < FAILOVERS
                        ? credentials.failover(PROVIDER, choice, e.getMessage()) : Optional.empty();
                if (next.isEmpty()) {
                    throw new IllegalStateException("转写失败：" + e.getMessage());
                }
                choice = next.get();
            }
        }
    }

    static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
