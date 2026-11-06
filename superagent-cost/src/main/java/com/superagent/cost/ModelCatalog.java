package com.superagent.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a public model directory (models.dev's format) says about each model: list price and release date. The same
 * model is sold by many resellers at many prices, so the vendor's own entry wins, and without one the middle price.
 */
final class ModelCatalog {

    /** USD per million tokens; {@code released} is an ISO date or null. */
    record Fact(double input, double output, String released, String source) {
        /** One number to compare models by: typical work reads about three tokens for each it writes. */
        double blended() {
            return (3 * input + output) / 4;
        }
    }

    private static final List<String> VENDORS = List.of("openai", "anthropic", "deepseek", "moonshotai", "moonshotai-cn",
            "zai", "zhipuai", "alibaba", "alibaba-cn", "google", "xai", "mistral", "minimax", "minimax-cn");

    private final Map<String, Fact> facts;

    private ModelCatalog(Map<String, Fact> facts) {
        this.facts = facts;
    }

    static ModelCatalog parse(InputStream json) throws IOException {
        Map<String, List<Fact>> offers = new HashMap<>();
        JsonNode root = new ObjectMapper().readTree(json);
        root.fields().forEachRemaining(provider -> provider.getValue().path("models").fields().forEachRemaining(model -> {
            JsonNode cost = model.getValue().path("cost");
            if (cost.path("input").isNumber() && cost.path("output").isNumber()) {
                String released = model.getValue().path("release_date").asText("");
                offers.computeIfAbsent(key(model.getKey()), k -> new ArrayList<>()).add(new Fact(
                        cost.path("input").asDouble(), cost.path("output").asDouble(),
                        released.isBlank() ? null : released, provider.getKey()));
            }
        }));
        Map<String, Fact> facts = new HashMap<>();
        offers.forEach((key, list) -> facts.put(key, best(list)));
        return new ModelCatalog(facts);
    }

    private static Fact best(List<Fact> offers) {
        for (String vendor : VENDORS) {
            for (Fact f : offers) {
                if (f.source().equals(vendor)) {
                    return f;
                }
            }
        }
        List<Fact> sorted = new ArrayList<>(offers);
        sorted.sort(Comparator.comparingDouble(Fact::blended));
        return sorted.get(sorted.size() / 2);
    }

    Optional<Fact> find(String modelId) {
        return Optional.ofNullable(facts.get(key(modelId)));
    }

    int size() {
        return facts.size();
    }

    /** The same model under other spellings: vendor/ prefixes, [variants], date suffixes, 5.5 and 5-5. */
    static String key(String modelId) {
        String k = modelId.strip().toLowerCase(Locale.ROOT);
        k = k.substring(k.lastIndexOf('/') + 1);
        int bracket = k.indexOf('[');
        if (bracket > 0) {
            k = k.substring(0, bracket);
        }
        k = k.replaceFirst("-(\\d{8}|\\d{4}-\\d{2}-\\d{2}|latest)$", "");
        return k.replace('.', '-');
    }
}
