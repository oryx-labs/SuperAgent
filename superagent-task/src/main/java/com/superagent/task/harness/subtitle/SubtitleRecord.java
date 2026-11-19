package com.superagent.task.harness.subtitle;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.superagent.core.media.Transcript;
import com.superagent.core.media.TranscriptAligner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a subtitle task worked out, kept as {@value #FILE} in its directory (too big for the harness state): the
 * transcripts, how they lined up, the decision on every disputed span, and what was delivered.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
final class SubtitleRecord {

    static final String FILE = "subtitle.json";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** A page of a reference document. */
    record Slide(String file, int page, String text) {
    }

    /** A term as the models were told it. */
    record Term(String term, List<String> aliases) {
    }

    /**
     * One disputed span: what each transcript had ({@code heard}, in the order of {@link #models}), what each text model
     * made of it ({@code calibrated}), what the subtitle has ({@code text}) and why. {@code doubt}: no majority, so a
     * person should look; {@code userText}: what they changed it to.
     */
    record Decision(String id, int cue, double start, double end, List<String> heard, Map<String, String> calibrated,
                    String text, boolean adopted, boolean doubt, String reason, String userText, Integer slide) {

        String shown() {
            return userText != null ? userText : text;
        }

        Decision fixedBy(String text) {
            return new Decision(id, cue, start, end, heard, calibrated, this.text, adopted, doubt, reason, text, slide);
        }
    }

    String audio;
    String audioName;
    List<String> references = new ArrayList<>();
    List<Slide> slides = new ArrayList<>();
    List<Term> terms = new ArrayList<>();
    int termsFound;
    int termsAdded;
    List<Transcript> transcripts = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    /** The transcript the timings come from. */
    int skeleton;
    List<String> models = new ArrayList<>();
    List<TranscriptAligner.AlignedCue> cues = new ArrayList<>();
    /** Only one transcript: every cue was in question, not just where versions differed. */
    boolean single;
    List<String> calibrators = new ArrayList<>();
    Map<String, Decision> decisions = new LinkedHashMap<>();
    /** A model's re-breaking of an aligned cue into several, by its index (dropped when a person edits that cue). */
    Map<Integer, List<String>> breaks = new LinkedHashMap<>();
    String srtFileId;
    String doubtsFileId;

    double seconds() {
        return transcripts.isEmpty() ? 0 : transcripts.get(skeleton).seconds();
    }

    List<String> termList() {
        return terms.stream().map(Term::term).toList();
    }

    /** Each aligned cue's text as decided (a person's fix over the models'). */
    List<SubtitleChecks.Cue> decided() {
        List<SubtitleChecks.Cue> out = new ArrayList<>();
        for (TranscriptAligner.AlignedCue c : cues) {
            List<String> parts = breaks.get(c.index());
            if (parts != null) {
                double total = parts.stream().mapToDouble(com.superagent.core.media.TextTokens::width).sum();
                double at = c.start();
                for (String p : parts) {
                    double end = total <= 0 ? c.end() : at + (c.end() - c.start()) * com.superagent.core.media.TextTokens.width(p) / total;
                    out.add(new SubtitleChecks.Cue(c.index(), at, end, p));
                    at = end;
                }
                continue;
            }
            String text = "";
            for (TranscriptAligner.Span s : c.spans()) {
                text = com.superagent.core.media.TextTokens.concat(text,
                        s.disputed() ? decisions.containsKey(s.id()) ? decisions.get(s.id()).shown() : s.text() : s.text());
            }
            out.add(new SubtitleChecks.Cue(c.index(), c.start(), c.end(), text));
        }
        return out;
    }

    /** Where the skeleton heard speech: from its first cue with words to its last. */
    double[] speech() {
        List<Transcript.Cue> spoken = transcripts.isEmpty() ? List.of()
                : transcripts.get(skeleton).cues().stream().filter(c -> c.text() != null && !c.text().isBlank()).toList();
        return spoken.isEmpty() ? new double[]{0, 0} : new double[]{spoken.get(0).start(), spoken.get(spoken.size() - 1).end()};
    }

    static SubtitleRecord read(Path dir) {
        Path f = dir.resolve(FILE);
        try {
            return Files.exists(f) ? JSON.readValue(Files.readString(f, StandardCharsets.UTF_8), SubtitleRecord.class)
                    : new SubtitleRecord();
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable subtitle record: " + e.getMessage(), e);
        }
    }

    void write(Path dir) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(this), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
