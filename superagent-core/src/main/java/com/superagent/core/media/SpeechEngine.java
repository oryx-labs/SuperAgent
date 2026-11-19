package com.superagent.core.media;

import com.superagent.core.usage.UsageContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One way of turning speech into text (OpenAI's transcription API; a local open-source program later). Adding an
 * engine is adding an implementation: the model check probes it, and subtitle tasks use what answered.
 */
public interface SpeechEngine {

    String id();

    /**
     * The model provider whose check probes this engine (its key is what it runs on, e.g. {@code codex} for OpenAI);
     * null: it needs no key, and every check probes it (a program on this server).
     */
    String provider();

    /** Tries each model it offers, cheaply, on the current company's keys. */
    List<Probe> probe();

    /**
     * {@code prompt}: terms the model should spell right (null: none); {@code language}: ISO-639-1 or null to detect.
     * A problem the user can fix (no key, a file it can't take) is an {@link IllegalArgumentException}.
     */
    Transcript transcribe(String model, Path file, String prompt, String language, UsageContext paying) throws IOException;

    record Probe(String model, boolean timed, boolean ok, String error) {
    }
}
