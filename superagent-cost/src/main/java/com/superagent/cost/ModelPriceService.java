package com.superagent.cost;

import com.superagent.api.provider.ModelPricesDto;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.provider.ModelRouter;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.provider.ProviderService;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What each model that can run costs. Tasks are matched to the cheapest model able to do them, token usage is turned
 * into money, and the system's small jobs go to the cheapest model, all by these prices. Worked out afresh on every
 * use from what can run at that moment, so a model that starts passing its probe is in on the next call.
 *
 * <p>Prices come from what the admin set by hand ({@code cost.price.<model>}: a relay may bill its own prices), else the
 * last directory download, else what was saved for models seen before ({@code cost.fact.<model>}), else the snapshot in
 * the jar. The directory is read daily, and hourly while a model that can run has no price. The admin can keep a model
 * out of tasks ({@code cost.task-off.<model>}).
 */
@Service
public class ModelPriceService implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelPriceService.class);
    private static final String FACT = "cost.fact.";
    private static final String MANUAL = "cost.price.";
    private static final String TASK_OFF = "cost.task-off.";
    private static final Duration FULL_REFRESH = Duration.ofHours(24);
    private static final Duration RETRY_UNPRICED = Duration.ofHours(1);

    /**
     * A model that can run now and what it costs (USD per million tokens; prices null when unknown). {@code released}
     * is its release date as the directory has it; {@code forTasks} whether tasks may run on it.
     */
    public record Candidate(String id, String cli, Double input, Double output, Double cacheRead, Double cacheWrite,
                            String released, String source, boolean manual, boolean forTasks) {
        /** One number to compare by (three tokens read per one written); unknown prices sort last. */
        public double blended() {
            return input == null || output == null ? Double.MAX_VALUE : (3 * input + output) / 4;
        }

        public boolean priced() {
            return input != null && output != null;
        }
    }

    /** List prices in USD per million tokens; the cache ones are null when nobody listed them. */
    public record Price(double input, double output, Double cacheRead, Double cacheWrite, String source) {
    }

    private final ProviderService providers;
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
    public ModelPriceService(ProviderService providers, SystemSettingStore store,
                             @Value("${superagent.cost.catalog-url:https://models.dev/api.json}") String catalogUrl,
                             @Value("${superagent.cost.catalog-check-seconds:600}") long checkSeconds) {
        this.providers = providers;
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
                if (due()) {
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

    // --- what can run, at what price ---

    /** Every model that can run now, cheapest first (unpriced last). */
    public List<Candidate> candidates() {
        Map<String, String> saved = store.all();
        Map<String, String> clis = new LinkedHashMap<>();
        for (ProviderCatalog.Entry e : ProviderCatalog.all()) {
            for (ProviderModelDto m : providers.storedModels(e.key())) {
                if (m.canInfer()) {
                    providers.cliFor(m.id()).ifPresent(cli -> clis.putIfAbsent(m.id(), cli));
                }
            }
        }
        return clis.entrySet().stream().map(e -> {
            String id = e.getKey();
            Optional<ModelCatalog.Fact> manual = decode(saved.get(MANUAL + id));
            Optional<ModelCatalog.Fact> f = manual.or(() -> fact(id, saved));
            boolean forTasks = !"true".equals(saved.get(TASK_OFF + id));
            return f.map(x -> new Candidate(id, e.getValue(), x.input(), x.output(), x.cacheRead(), x.cacheWrite(),
                            x.released(), x.source(), manual.isPresent(), forTasks))
                    .orElse(new Candidate(id, e.getValue(), null, null, null, null, null, null, false, forTasks));
        }).sorted(Comparator.comparingDouble(Candidate::blended).thenComparing(Candidate::id)).toList();
    }

    /** What {@code model} costs: what the admin set, else its list price. */
    public Optional<Price> price(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> saved = store.all();
        String id = model.strip();
        return decode(saved.get(MANUAL + id)).or(() -> fact(id, saved))
                .map(f -> new Price(f.input(), f.output(), f.cacheRead(), f.cacheWrite(), f.source()));
    }

    @Override
    public Optional<ModelChoice> cheapest() {
        return candidates().stream().filter(Candidate::priced).findFirst()
                .or(() -> candidates().stream().findFirst())
                .map(c -> new ModelChoice(c.id(), c.cli()));
    }

    private Optional<ModelCatalog.Fact> fact(String id, Map<String, String> saved) {
        ModelCatalog latest = downloaded;
        Optional<ModelCatalog.Fact> f = latest == null ? Optional.empty() : latest.find(id);
        return f.or(() -> decode(saved.get(FACT + id))).or(() -> bundled.find(id));
    }

    // --- the directory ---

    private boolean due() {
        Instant now = Instant.now();
        if (downloadedAt == null || downloadedAt.plus(FULL_REFRESH).isBefore(now)) {
            return triedAt == null || triedAt.plus(RETRY_UNPRICED).isBefore(now);
        }
        boolean unpriced = candidates().stream().anyMatch(c -> !c.priced());
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
        for (Candidate c : candidates()) {
            catalog.find(c.id()).map(ModelPriceService::encode).filter(v -> !v.equals(saved.get(FACT + c.id())))
                    .ifPresent(v -> store.put(FACT + c.id(), v));
        }
        log.info("model price directory read: {} models", catalog.size());
    }

    private static String encode(ModelCatalog.Fact f) {
        return f.input() + "|" + f.output() + "|" + (f.released() == null ? "" : f.released()) + "|" + f.source()
                + "|" + (f.cacheRead() == null ? "" : f.cacheRead()) + "|" + (f.cacheWrite() == null ? "" : f.cacheWrite());
    }

    private static Optional<ModelCatalog.Fact> decode(String v) {
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        String[] p = v.split("\\|", -1);
        try {
            return Optional.of(new ModelCatalog.Fact(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                    p.length > 2 && !p[2].isEmpty() ? p[2] : null, p.length > 3 ? p[3] : "manual",
                    optionalPrice(p, 4), optionalPrice(p, 5))); // older saves have 4 parts
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Double optionalPrice(String[] parts, int i) {
        return parts.length > i && !parts[i].isEmpty() ? Double.valueOf(parts[i]) : null;
    }

    // --- the admin's page ---

    public ModelPricesDto overview() {
        List<ModelPricesDto.Model> models = candidates().stream().map(c -> new ModelPricesDto.Model(c.id(), c.cli(),
                c.input(), c.output(), c.cacheRead(), c.cacheWrite(), c.released(), c.source(), c.manual(), c.forTasks())).toList();
        return new ModelPricesDto(models, downloadedAt, catalogError);
    }

    /**
     * Sets {@code model}'s prices by hand (a relay's own prices, say), USD per million tokens; null input/output goes
     * back to the list price.
     */
    public ModelPricesDto setPrice(String model, Double input, Double output, Double cacheRead, Double cacheWrite) {
        String id = requireModel(model);
        if (input == null || output == null) {
            store.put(MANUAL + id, null);
            return overview();
        }
        if (input < 0 || output < 0 || (cacheRead != null && cacheRead < 0) || (cacheWrite != null && cacheWrite < 0)) {
            throw new IllegalArgumentException("A price can't be negative");
        }
        store.put(MANUAL + id, input + "|" + output + "||manual|" + (cacheRead == null ? "" : cacheRead) + "|"
                + (cacheWrite == null ? "" : cacheWrite));
        return overview();
    }

    /** Lets tasks run on {@code model}, or keeps them off it (it still serves chats if it's the default model). */
    public ModelPricesDto setForTasks(String model, boolean on) {
        store.put(TASK_OFF + requireModel(model), on ? null : "true");
        return overview();
    }

    private static String requireModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("A model is required");
        }
        return model.strip();
    }

    /** For tests: the prices of a directory, as if just downloaded. */
    void useCatalog(ModelCatalog catalog) {
        downloaded = catalog;
        downloadedAt = Instant.now();
    }
}
