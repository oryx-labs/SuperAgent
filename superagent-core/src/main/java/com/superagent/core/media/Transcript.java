package com.superagent.core.media;

import java.util.List;

/**
 * What one speech model heard in one audio file. {@code timed}: the cues carry real timings (a model without timings
 * returns its whole text as one cue spanning the audio). {@code usd}: what the call cost.
 */
public record Transcript(String model, boolean timed, List<Cue> cues, double seconds, double usd) {

    public record Cue(double start, double end, String text) {
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Cue c : cues) {
            String t = c.text() == null ? "" : c.text().strip();
            if (!t.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(t);
            }
        }
        return sb.toString();
    }
}
