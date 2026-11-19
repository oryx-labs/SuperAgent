package com.superagent.media;

import com.superagent.core.media.SpeechEngine;
import com.superagent.core.media.SpeechModel;
import com.superagent.core.media.SpeechToText;
import com.superagent.core.media.Transcript;
import com.superagent.core.usage.UsageContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Hands a speech model's job to the engine it runs on. */
@Service
public class SpeechDispatcher implements SpeechToText {

    private final List<SpeechEngine> engines;

    public SpeechDispatcher(List<SpeechEngine> engines) {
        this.engines = engines;
    }

    @Override
    public Transcript transcribe(SpeechModel model, Path file, String prompt, String language, UsageContext paying)
            throws IOException {
        SpeechEngine engine = engines.stream().filter(e -> e.id().equals(model.engine())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有听写引擎 " + model.engine()));
        return engine.transcribe(model.model(), file, prompt, language, paying);
    }
}
