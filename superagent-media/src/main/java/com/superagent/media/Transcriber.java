package com.superagent.media;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.usage.UsageContext;
import com.superagent.core.usage.UsageRecorder;
import com.superagent.platform.provider.CredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Speech to text with timings, on the company's own OpenAI keys (the Codex pool: the same account, and its failover).
 * The platform makes the call, so the key never reaches an agent's shell. Long or unusual files are cut into mono mp3
 * pieces with ffmpeg first, when the server has it; each piece's timings are shifted back onto the whole.
 */
@Service
public class Transcriber {

    static final String PROVIDER = "codex";
    static final String MODEL = "whisper-1";
    /** OpenAI's list price for whisper-1. */
    static final double USD_PER_MINUTE = 0.006;
    /** The API takes 25 MB a file; a little room for the form around it. */
    static final long MAX_BYTES = 24L * 1024 * 1024;
    static final int PIECE_SECONDS = 1200;
    static final Set<String> ACCEPTED = Set.of("flac", "m4a", "mp3", "mp4", "mpeg", "mpga", "oga", "ogg", "wav", "webm");
    private static final int FAILOVERS = 3;

    public record Segment(double start, double end, String text) {
    }

    /** What the API returned for one piece. */
    record Piece(double seconds, List<Segment> segments) {
    }

    /** The call itself, a seam for tests. */
    interface Api {
        Piece transcribe(RunCredential credential, Path file, String language) throws ApiException;
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
    private final Ffmpeg ffmpeg; // null: the server has none, so only files the API takes as they are
    private UsageRecorder usage; // null in tests

    @Autowired
    public Transcriber(CredentialService credentials) {
        this(credentials, new OpenAiTranscriptions(), Ffmpeg.find());
    }

    Transcriber(CredentialService credentials, Api api, Ffmpeg ffmpeg) {
        this.credentials = credentials;
        this.api = api;
        this.ffmpeg = ffmpeg;
    }

    @Autowired(required = false)
    void setUsage(UsageRecorder usage) {
        this.usage = usage;
    }

    /**
     * {@code language}: ISO-639-1 ("zh", "en"), or null to detect it. {@code paying}: who the minutes go on. A problem
     * the user can fix (no key, a file it can't take) is an {@link IllegalArgumentException}.
     */
    public Result transcribe(Path file, String language, UsageContext paying) throws IOException {
        String ext = extension(file);
        long size = Files.size(file);
        Path scratch = null;
        try {
            List<Path> pieces;
            if (size <= MAX_BYTES && ACCEPTED.contains(ext)) {
                pieces = List.of(file);
            } else if (ffmpeg == null) {
                throw new IllegalArgumentException((size > MAX_BYTES
                        ? String.format(Locale.ROOT, "这个文件有 %.0f MB，超过转写单次上限 24 MB", size / 1048576.0)
                        : "转写不直接支持 ." + ext + " 格式（支持 " + String.join("、", ACCEPTED.stream().sorted().toList()) + "）")
                        + "。服务器装上 ffmpeg 后平台会自动转换、切段；现在可以先把它转成 mp3 并压到 24 MB 以内再转写");
            } else {
                scratch = Files.createTempDirectory("sa-transcribe-");
                pieces = ffmpeg.split(file, scratch, PIECE_SECONDS);
            }
            List<Segment> all = new ArrayList<>();
            double offset = 0;
            double usd = 0;
            for (Path piece : pieces) {
                Piece p = call(piece, language);
                for (Segment s : p.segments()) {
                    all.add(new Segment(s.start() + offset, s.end() + offset, s.text()));
                }
                offset += p.seconds();
                double cost = p.seconds() / 60 * USD_PER_MINUTE;
                usd += cost;
                if (usage != null && paying != null) {
                    usage.recordCost(paying, MODEL, cost); // per piece: a later failure doesn't hide what was spent
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

    private Piece call(Path piece, String language) {
        CredentialService.Choice choice;
        try {
            choice = credentials.current(PROVIDER);
        } catch (CredentialService.NoCompanyKeyException e) {
            throw new IllegalArgumentException("语音转写用的是公司的 OpenAI Key（「模型」里 OpenAI Codex 那一组），还没有配置：请管理员先添加");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return api.transcribe(choice.credential(), piece, language);
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
