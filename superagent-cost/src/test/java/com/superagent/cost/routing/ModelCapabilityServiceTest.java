package com.superagent.cost.routing;

import com.superagent.core.media.ModelCapabilities.TextModel;
import com.superagent.core.media.SpeechEngine;
import com.superagent.core.media.SpeechModel;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.cost.ModelPriceService;
import com.superagent.cost.ModelPriceService.Candidate;
import com.superagent.platform.provider.SpeechProbes;
import com.superagent.platform.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Models grouped by what was checked: speech by its probe, text by the inference check; calibrators one per vendor. */
class ModelCapabilityServiceTest {

    private final ModelPriceService prices = mock(ModelPriceService.class);
    private final SettingsService settings = mock(SettingsService.class);

    private static Candidate model(String id, String cli, Double in, boolean forTasks) {
        return new Candidate(id, cli, in, in == null ? null : in * 4, null, null, "2026-05-01", "x", false, forTasks);
    }

    private SpeechProbes probes(SpeechEngine... engines) {
        Map<String, String> saved = new HashMap<>();
        SystemSettingStore store = new SystemSettingStore() {
            @Override public Map<String, String> all() { return saved; }
            @Override public void put(String key, String value) { saved.put(key, value); }
        };
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < engines.length; i++) {
            beans.registerSingleton("engine" + i, engines[i]);
        }
        return new SpeechProbes(store, beans.getBeanProvider(SpeechEngine.class));
    }

    private static SpeechEngine engine(String id, String provider, List<SpeechEngine.Probe> probes) {
        return new SpeechEngine() {
            @Override public String id() { return id; }
            @Override public String provider() { return provider; }
            @Override public List<Probe> probe() { return probes; }
            @Override public com.superagent.core.media.Transcript transcribe(String m, java.nio.file.Path f, String p,
                                                                            String l, com.superagent.core.usage.UsageContext u) {
                throw new AssertionError();
            }
        };
    }

    @Test
    void speechModelsComeFromTheirProbeAndTextModelsFromTheInferenceCheck() {
        SpeechProbes speech = probes(engine("openai", "codex", List.of(new SpeechEngine.Probe("gpt-4o-transcribe", false, true, null),
                        new SpeechEngine.Probe("whisper-1", true, true, null),
                        new SpeechEngine.Probe("gpt-4o-mini-transcribe", false, false, "HTTP 404"))),
                engine("local", null, List.of()));
        speech.check("claude", Duration.ofHours(1));
        assertThat(speech.available()).isEmpty(); // another provider's check doesn't probe the OpenAI key
        speech.check("codex", Duration.ofHours(1));
        when(prices.candidates()).thenReturn(List.of(model("deepseek-v3", "deepseek", 0.3, true),
                model("claude-haiku-5-5", "claude", 1.0, false), model("claude-opus-5-5", "claude", 15.0, true)));
        ModelCapabilityService caps = new ModelCapabilityService(prices, settings, speech);

        assertThat(caps.transcribers()).containsExactly(new SpeechModel("openai", "whisper-1", true),
                new SpeechModel("openai", "gpt-4o-transcribe", false));
        assertThat(caps.textModels()).extracting(TextModel::model).containsExactly("deepseek-v3", "claude-opus-5-5");
    }

    @Test
    void calibratorsAreTheStrongestOfEachVendorUpToTheLimitAndWithinFixedModels() {
        when(prices.candidates()).thenReturn(List.of(model("glm-4.6", "glm", 0.5, true), model("deepseek-v3", "deepseek", 0.3, true),
                model("claude-sonnet-5-5", "claude", 3.0, true), model("claude-opus-5-5", "claude", 15.0, true),
                model("gpt-5.5", "codex", 5.0, true), model("kimi-k2", "kimi", null, true)));
        when(settings.taskFixedModels()).thenReturn(List.of());
        when(settings.subtitleMaxCalibrators()).thenReturn(3);
        ModelCapabilityService caps = new ModelCapabilityService(prices, settings, probes());

        assertThat(caps.calibrators()).extracting(TextModel::model, TextModel::vendor).containsExactly(
                org.assertj.core.groups.Tuple.tuple("claude-opus-5-5", "anthropic"),
                org.assertj.core.groups.Tuple.tuple("gpt-5.5", "openai"),
                org.assertj.core.groups.Tuple.tuple("glm-4.6", "zhipu"));

        when(settings.subtitleMaxCalibrators()).thenReturn(10);
        assertThat(caps.calibrators()).hasSize(5); // one per vendor, however high the limit

        when(settings.taskFixedModels()).thenReturn(List.of("claude-sonnet-5-5", "deepseek-v3"));
        assertThat(caps.calibrators()).extracting(TextModel::model).containsExactly("claude-sonnet-5-5", "deepseek-v3");
    }
}
