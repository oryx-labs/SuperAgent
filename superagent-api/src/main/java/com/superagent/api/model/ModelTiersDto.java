package com.superagent.api.model;

import java.time.Instant;
import java.util.List;

/**
 * Model tiers. {@code tiers}: what can be picked, each with the model it means now. {@code models}: for the admin,
 * every model that can run and where it landed; a null price means the directory doesn't know it.
 */
public record ModelTiersDto(boolean enabled, String defaultTier, List<Tier> tiers, List<Model> models,
                            Instant pricesReadAt, String pricesError) {

    public record Tier(String tier, String model, String cli) {
    }

    /** Prices are USD per million tokens; {@code autoTier} is where the prices put it, {@code manual} that the admin moved it. */
    public record Model(String id, String cli, String tier, String autoTier, boolean manual, Double input, Double output,
                        String released, String source) {
    }
}
