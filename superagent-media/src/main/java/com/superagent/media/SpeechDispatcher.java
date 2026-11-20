package com.superagent.media;

import com.superagent.core.error.NeedsUserException;
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
    private final Transcriber transcriber;

    public SpeechDispatcher(List<SpeechEngine> engines, Transcriber transcriber) {
        this.engines = engines;
        this.transcriber = transcriber;
    }

    @Override
    public Limits limits() {
        return transcriber.converts() ? new Limits(true, Long.MAX_VALUE, Transcriber.ACCEPTED)
                : new Limits(false, Transcriber.MAX_BYTES, Transcriber.ACCEPTED);
    }

    @Override
    public Transcript transcribe(SpeechModel model, Path file, String prompt, String language, UsageContext paying)
            throws IOException {
        SpeechEngine engine = engines.stream().filter(e -> e.id().equals(model.engine())).findFirst()
                .orElseThrow(() -> new NeedsUserException("没有听写引擎 " + model.engine()
                        + "：请管理员在「模型」里重新检查听写模型，然后回复「继续」"));
        return engine.transcribe(model.model(), file, prompt, language, paying);
    }
}
