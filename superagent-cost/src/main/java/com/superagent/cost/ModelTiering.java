package com.superagent.cost;

import com.superagent.core.provider.ModelTier;

import java.util.HashMap;
import java.util.Map;

/**
 * Sorts the models that can run now into tiers by price, relative to each other: from the cheapest to the dearest
 * in three equal multiples. Relative because each company runs different models: one with only DeepSeek still gets
 * a fast and a strong one. Multiples because model prices span orders of magnitude.
 */
final class ModelTiering {

    private static final double FREE = 0.01; // a free model has no multiple to measure from
    private static final double SAME_PRICE = 1.5; // closer than this, there's nothing to tell apart

    /** Prices by model id (blended USD per million tokens); models with no known price aren't in here. */
    static Map<String, ModelTier> byPrice(Map<String, Double> prices) {
        Map<String, ModelTier> out = new HashMap<>();
        double min = prices.values().stream().mapToDouble(p -> Math.max(p, FREE)).min().orElse(FREE);
        double max = prices.values().stream().mapToDouble(p -> Math.max(p, FREE)).max().orElse(FREE);
        prices.forEach((id, price) -> {
            if (max / min < SAME_PRICE) {
                out.put(id, ModelTier.STANDARD);
                return;
            }
            double position = Math.log(Math.max(price, FREE) / min) / Math.log(max / min);
            out.put(id, position < 1.0 / 3 ? ModelTier.FAST : position < 2.0 / 3 ? ModelTier.STANDARD : ModelTier.STRONG);
        });
        return out;
    }

    private ModelTiering() {
    }
}
