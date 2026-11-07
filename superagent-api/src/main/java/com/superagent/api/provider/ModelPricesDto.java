package com.superagent.api.provider;

import java.time.Instant;
import java.util.List;

/**
 * What each model that can run costs, for the admin. Prices are USD per million tokens; null when nobody knows them.
 * {@code manual}: the admin set them; {@code forTasks}: tasks may run on it.
 */
public record ModelPricesDto(List<Model> models, Instant pricesReadAt, String pricesError) {

    public record Model(String id, String cli, Double input, Double output, Double cacheRead, Double cacheWrite,
                        String released, String source, boolean manual, boolean forTasks) {
    }

    /** A price set by hand; null input or output goes back to the list price. */
    public record SetPrice(Double input, Double output, Double cacheRead, Double cacheWrite) {
    }

    public record SetForTasks(boolean forTasks) {
    }
}
