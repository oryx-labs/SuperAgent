package com.superagent.media;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.media.SpeechEngine;
import com.superagent.core.media.Transcript;
import com.superagent.core.usage.UsageContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

/** OpenAI's transcription models on the company's OpenAI keys (the Codex pool), probed when that pool is checked. */
@Component
public class OpenAiSpeechEngine implements SpeechEngine {

    public static final String ID = "openai";

    private final Transcriber transcriber;
    private final BiConsumer<RunCredential, String> send;

    @Autowired
    public OpenAiSpeechEngine(Transcriber transcriber) {
        this(transcriber, probeWith(new OpenAiTranscriptions()));
    }

    OpenAiSpeechEngine(Transcriber transcriber, BiConsumer<RunCredential, String> send) {
        this.transcriber = transcriber;
        this.send = send;
    }

    private static BiConsumer<RunCredential, String> probeWith(OpenAiTranscriptions api) {
        return (credential, model) -> {
            try {
                api.probe(credential, model);
            } catch (Transcriber.ApiException e) {
                throw new IllegalStateException(e.getMessage());
            }
        };
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String provider() {
        return Transcriber.PROVIDER;
    }

    @Override
    public List<Probe> probe() {
        return transcriber.probe(send);
    }

    @Override
    public Transcript transcribe(String model, Path file, String prompt, String language, UsageContext paying) throws IOException {
        Transcriber.Result r = transcriber.transcribe(model, file, prompt, language, paying);
        return new Transcript(model, Transcriber.timed(model),
                r.segments().stream().map(s -> new Transcript.Cue(s.start(), s.end(), s.text())).toList(), r.seconds(), r.usd());
    }
}
