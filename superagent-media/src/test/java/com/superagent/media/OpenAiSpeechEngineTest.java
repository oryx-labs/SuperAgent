package com.superagent.media;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.media.SpeechEngine;
import com.superagent.platform.provider.CredentialService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The probe tries every OpenAI transcription model on the company's key; only whisper-1 has timings. */
class OpenAiSpeechEngineTest {

    @Test
    void eachModelIsProbedOnTheCompanysKey() throws Exception {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current("codex")).thenReturn(new CredentialService.Choice(new RunCredential("k1", null, "a", true)));
        Transcriber transcriber = new Transcriber(credentials, (c, f, r) -> null, null);
        OpenAiSpeechEngine engine = new OpenAiSpeechEngine(transcriber, (credential, model) -> {
            if (model.equals("gpt-4o-mini-transcribe")) {
                throw new IllegalStateException("HTTP 404: model not found");
            }
        });

        List<SpeechEngine.Probe> probes = engine.probe();

        assertThat(probes).extracting(SpeechEngine.Probe::model, SpeechEngine.Probe::timed, SpeechEngine.Probe::ok)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("gpt-4o-mini-transcribe", false, false),
                        org.assertj.core.groups.Tuple.tuple("gpt-4o-transcribe", false, true),
                        org.assertj.core.groups.Tuple.tuple("whisper-1", true, true));
        assertThat(OpenAiTranscriptions.silence()).hasSize(44 + 32000);
        assertThat(OpenAiTranscriptions.parse("{\"text\":\"你好 world\",\"usage\":{\"type\":\"duration\",\"seconds\":4}}").seconds())
                .isEqualTo(4);
    }
}
