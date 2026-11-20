package com.superagent.core.media;

import com.superagent.core.usage.UsageContext;

import java.io.IOException;
import java.nio.file.Path;

/** Runs a speech model on its engine. Implemented by the media module; tasks only see this. */
public interface SpeechToText {

    /**
     * A problem the user must fix first (a file too big without ffmpeg, no key) is a
     * {@link com.superagent.core.error.NeedsUserException}; anything else may be worth another try.
     */
    Transcript transcribe(SpeechModel model, Path file, String prompt, String language, UsageContext paying) throws IOException;

    /** What files can go in as they are; with a converter (ffmpeg) on the server, any size or format. */
    default Limits limits() {
        return new Limits(false, Long.MAX_VALUE, java.util.Set.of());
    }

    /** {@code converts}: the server can convert and cut files; otherwise at most {@code maxBytes}, in {@code formats}. */
    record Limits(boolean converts, long maxBytes, java.util.Set<String> formats) {
    }
}
