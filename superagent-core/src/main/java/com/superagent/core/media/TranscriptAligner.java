package com.superagent.core.media;

import java.util.List;

/**
 * Lines several transcripts of the same audio up against one of them (the skeleton, which has the timings): Chinese
 * character by character, other languages word by word, ignoring case and punctuation. Implemented by the media module.
 */
public interface TranscriptAligner {

    /** {@code versions.get(skeleton)} gives the cues and timings; the rest are compared against it. */
    Alignment align(List<Transcript> versions, int skeleton);

    /**
     * {@code cues}: the skeleton's cues, each as spans where all versions agree and spans where they don't.
     * {@code models}: the versions' models, in the order of every disputed span's {@code versions}.
     */
    record Alignment(List<String> models, List<AlignedCue> cues) {

        public long disputes() {
            return cues.stream().flatMap(c -> c.spans().stream()).filter(Span::disputed).count();
        }

        public long agreedCues() {
            return cues.stream().filter(c -> c.spans().stream().noneMatch(Span::disputed)).count();
        }
    }

    record AlignedCue(int index, double start, double end, List<Span> spans) {
    }

    /**
     * {@code id} null: every version has {@code text} here. Otherwise {@code versions} holds what each one has here
     * (an empty string where one has nothing), and {@code text} is the skeleton's.
     */
    record Span(String id, String text, List<String> versions) {

        public static Span agreed(String text) {
            return new Span(null, text, List.of());
        }

        public boolean disputed() {
            return id != null;
        }
    }
}
