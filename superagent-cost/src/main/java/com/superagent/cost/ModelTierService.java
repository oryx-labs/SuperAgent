package com.superagent.cost;

import com.superagent.api.model.ModelTiersDto;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.provider.ModelRouter;
import com.superagent.core.provider.ModelTier;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Which model each tier means right now. Worked out afresh on every use from what can run at that moment (the
 * hourly probe's results), so a model that starts passing its probe takes its place on the next turn with nothing
 * to trigger or configure. Only prices need fetching: the directory is read daily, and sooner when a model that can
 * run has no price yet. Within a tier the newest model comes first: price isn't ability, and an older model can
 * cost more than its successor.
 *
 * <p>Prices come from the last directory download, else what was saved for models seen before (settings
 * {@code cost.fact.<model>}), else the snapshot in the jar. A model with no price goes in the standard tier, last,
 * flagged for the admin, who can place any model by hand ({@code cost.tier.<model>}), which also puts it first.
 */
@Service
public class ModelTierService implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelTierService.class);
    private static final String FACT = "cost.fact.";
    private static final String OVERRIDE = "cost.tier.";
    private static final Duration FULL_REFRESH = Duration.ofHours(24);
    private static final Duration RETRY_UNPRICED = Duration.ofHours(1);

    /** The cheaper tiers first: running out of a tier shouldn't quietly cost more if it can be helped. */
    private static final Map<ModelTier, List<ModelTier>> FALLBACK = Map.of(
            ModelTier.FAST, List.of(ModelTier.FAST, ModelTier.STANDARD, ModelTier.STRONG),
            ModelTier.STANDARD, List.of(ModelTier.STANDARD, ModelTier.FAST, ModelTier.STRONG),
            ModelTier.STRONG, List.of(ModelTier.STRONG, ModelTier.STANDARD, ModelTier.FAST));

    private record Placed(String id, String cli, ModelTier tier, ModelTier auto, boolean manual, ModelCatalog.Fact fact) {
    }

    private final ProviderService providers;
    private final SettingsService settings;
    private final SystemSettingStore store;
    private final String catalogUrl;
    private final long checkSeconds;
    private final ModelCatalog bundled;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "model-catalog");
        t.setDaemon(true);
        return t;
    });

    private volatile ModelCatalog downloaded;
    private volatile Instant downloadedAt;
    private volatile Instant triedAt;
    private volatile String catalogError;

    @Autowired
    public ModelTierService(ProviderService providers, SettingsService settings, SystemSettingStore store,
                            @Value("${superagent.cost.catalog-url:https://models.dev/api.json}") String catalogUrl,
                            @Value("${superagent.cost.catalog-check-seconds:600}") long checkSeconds) {
        this.providers = providers;
        this.settings = settings;
        this.store = store;
        this.catalogUrl = catalogUrl;
        this.checkSeconds = checkSeconds;
        try (InputStream in = getClass().getResourceAsStream("/model-catalog-snapshot.json")) {
            this.bundled = ModelCatalog.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (enabled() && due()) {
                    refresh();
                }
            } catch (RuntimeException e) {
                log.warn("model price check failed: {}", e.toString());
            }
        }, 0, checkSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // --- choosing ---

    @Override
    public boolean enabled() {
        return settings.tiersEnabled();
    }

    @Override
    public Optional<ModelChoice> choose(String tier, String keep) {
        ModelTier wanted = ModelTier.of(tier);
        if (wanted == null) {
            wanted = ModelTier.of(settings.defaultTier());
        }
        List<Placed> placed = placed();
        for (ModelTier t : FALLBACK.get(wanted)) {
            List<Placed> in = placed.stream().filter(p -> p.tier() == t).toList();
            if (!in.isEmpty()) {
                Placed use = in.stream().filter(p -> p.id().equals(keep)).findFirst().orElse(in.get(0));
                return Optional.of(new ModelChoice(wanted.key(), use.id(), use.cli()));
            }
        }
        return Optional.empty();
    }

    /** Every model that can run now, in its tier, in the order they'd be used. */
    private List<Placed> placed() {
        Map<String, String> saved = store.all();
        Map<String, String> clis = new LinkedHashMap<>();
        for (ProviderCatalog.Entry e : ProviderCatalog.all()) {
            for (ProviderModelDto m : providers.storedModels(e.key())) {
                if (m.canInfer()) {
                    providers.cliFor(m.id()).ifPresent(cli -> clis.putIfAbsent(m.id(), cli));
                }
            }
        }
        Map<String, ModelCatalog.Fact> facts = new HashMap<>();
        Map<String, Double> prices = new HashMap<>();
        clis.keySet().forEach(id -> fact(id, saved).ifPresent(f -> {
            facts.put(id, f);
            prices.put(id, f.blended());
        }));
        Map<String, ModelTier> auto = ModelTiering.byPrice(prices);
        List<Placed> out = new ArrayList<>();
        clis.forEach((id, cli) -> {
            ModelTier computed = auto.getOrDefault(id, ModelTier.STANDARD);
            ModelTier manual = manual(saved.get(OVERRIDE + id));
            out.add(new Placed(id, cli, manual != null ? manual : computed, computed, manual != null, facts.get(id)));
        });
        // By hand first (the admin wants it used: it's also the way to bring in a model with no price), then newest.
        out.sort(Comparator.comparing((Placed p) -> !p.manual())
                .thenComparing(p -> p.fact() == null)
                .thenComparing(p -> p.fact() == null || p.fact().released() == null ? "" : p.fact().released(), Comparator.reverseOrder())
                .thenComparingDouble(p -> p.fact() == null ? 0 : p.fact().blended())
                .thenComparing(Placed::id));
        return out;
    }

    private static ModelTier manual(String saved) {
        try {
            return ModelTier.of(saved);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Optional<ModelCatalog.Fact> fact(String id, Map<String, String> saved) {
        ModelCatalog latest = downloaded;
        Optional<ModelCatalog.Fact> f = latest == null ? Optional.empty() : latest.find(id);
        return f.or(() -> decode(saved.get(FACT + id))).or(() -> bundled.find(id));
    }

    // --- prices ---

    private boolean due() {
        Instant now = Instant.now();
        if (downloadedAt == null || downloadedAt.plus(FULL_REFRESH).isBefore(now)) {
            return triedAt == null || triedAt.plus(RETRY_UNPRICED).isBefore(now);
        }
        boolean unpriced = placed().stream().anyMatch(p -> p.fact() == null);
        return unpriced && triedAt.plus(RETRY_UNPRICED).isBefore(now);
    }

    /** Downloads the directory; a failure keeps the prices already known and shows on the admin page. */
    public void refresh() {
        triedAt = Instant.now();
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(catalogUrl))
                    .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = res.body()) {
                if (res.statusCode() != 200) {
                    throw new IOException("HTTP " + res.statusCode());
                }
                accept(ModelCatalog.parse(body));
            }
        } catch (IOException | RuntimeException e) {
            catalogError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("could not read the model price directory: {}", catalogError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Takes a fresh directory, saving the prices of the models in use so a restart without network still has them. */
    void accept(ModelCatalog catalog) {
        downloaded = catalog;
        downloadedAt = Instant.now();
        catalogError = null;
        Map<String, String> saved = store.all();
        for (Placed p : placed()) {
            catalog.find(p.id()).map(ModelTierService::encode).filter(v -> !v.equals(saved.get(FACT + p.id())))
                    .ifPresent(v -> store.put(FACT + p.id(), v));
        }
        log.info("model price directory read: {} models", catalog.size());
    }

    private static String encode(ModelCatalog.Fact f) {
        return f.input() + "|" + f.output() + "|" + (f.released() == null ? "" : f.released()) + "|" + f.source();
    }

    private static Optional<ModelCatalog.Fact> decode(String v) {
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        String[] p = v.split("\\|", -1);
        try {
            return Optional.of(new ModelCatalog.Fact(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                    p[2].isEmpty() ? null : p[2], p[3]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // --- pages ---

    /** What people may pick from: only tiers with a model of their own, each with the model it means now. */
    public List<ModelTiersDto.Tier> tiers() {
        List<Placed> placed = placed();
        Map<ModelTier, Placed> first = new EnumMap<>(ModelTier.class);
        placed.forEach(p -> first.putIfAbsent(p.tier(), p));
        List<ModelTiersDto.Tier> out = new ArrayList<>();
        for (ModelTier t : ModelTier.values()) {
            if (first.containsKey(t)) {
                out.add(new ModelTiersDto.Tier(t.key(), first.get(t).id(), first.get(t).cli()));
            }
        }
        return out;
    }

    /** The admin's view: which model landed where and why. */
    public ModelTiersDto overview() {
        List<ModelTiersDto.Model> models = placed().stream().map(p -> new ModelTiersDto.Model(p.id(), p.cli(),
                p.tier().key(), p.auto().key(), p.manual(), p.fact() == null ? null : p.fact().input(),
                p.fact() == null ? null : p.fact().output(), p.fact() == null ? null : p.fact().released(),
                p.fact() == null ? null : p.fact().source())).toList();
        return new ModelTiersDto(enabled(), settings.defaultTier(), tiers(), models, downloadedAt, catalogError);
    }

    /** Places a model by hand ({@code tier} blank goes back to automatic). */
    public ModelTiersDto place(String modelId, String tier) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("A model is required");
        }
        ModelTier t = ModelTier.of(tier);
        store.put(OVERRIDE + modelId.strip(), t == null ? null : t.key());
        return overview();
    }
}
