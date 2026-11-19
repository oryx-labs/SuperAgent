package com.superagent.core.media;

import com.superagent.core.usage.UsageContext;

import java.io.IOException;
import java.nio.file.Path;

/** Runs a speech model on its engine. Implemented by the media module; tasks only see this. */
public interface SpeechToText {

    Transcript transcribe(SpeechModel model, Path file, String prompt, String language, UsageContext paying) throws IOException;
}
