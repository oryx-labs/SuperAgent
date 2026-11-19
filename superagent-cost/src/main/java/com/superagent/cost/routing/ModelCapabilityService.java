package com.superagent.cost.routing;

import com.superagent.core.media.ModelCapabilities;
import com.superagent.core.media.SpeechModel;
import com.superagent.cost.ModelPriceService;
import com.superagent.cost.ModelPriceService.Candidate;
import com.superagent.platform.provider.SpeechProbes;
import com.superagent.platform.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups models by what they can do, from what was checked: speech models by their probe, text models by the
 * inference check and 「用于任务」. A subtitle's disputes are best settled by models trained apart, so its
 * calibrators are one per vendor, the strongest (dearest) of each, up to the company's limit.
 */
@Service
public class ModelCapabilityService implements ModelCapabilities {

    private final ModelPriceService prices;
    private final SettingsService settings;
    private final SpeechProbes speech;

    public ModelCapabilityService(ModelPriceService prices, SettingsService settings, SpeechProbes speech) {
        this.prices = prices;
        this.settings = settings;
        this.speech = speech;
    }

    @Override
    public List<SpeechModel> transcribers() {
        return speech.available();
    }

    @Override
    public List<TextModel> textModels() {
        return forTasks().stream().map(ModelCapabilityService::text).toList();
    }

    @Override
    public List<TextModel> calibrators() {
        List<String> fixed = settings.taskFixedModels();
        List<Candidate> pool = forTasks().stream().filter(c -> fixed.isEmpty() || fixed.contains(c.id())).toList();
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate c : pool) {
            best.merge(ModelVendors.of(c.id(), c.cli()), c, (a, b) -> strength(b) > strength(a) ? b : a);
        }
        List<Candidate> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(ModelCapabilityService::strength).reversed().thenComparing(Candidate::id));
        return out.stream().limit(Math.max(1, settings.subtitleMaxCalibrators())).map(ModelCapabilityService::text).toList();
    }

    private List<Candidate> forTasks() {
        return prices.candidates().stream().filter(Candidate::forTasks).toList();
    }

    /** Price as the stand-in for ability; an unpriced model counts as the weakest. */
    private static double strength(Candidate c) {
        return c.priced() ? c.blended() : -1;
    }

    private static TextModel text(Candidate c) {
        return new TextModel(c.id(), c.cli(), ModelVendors.of(c.id(), c.cli()));
    }
}
