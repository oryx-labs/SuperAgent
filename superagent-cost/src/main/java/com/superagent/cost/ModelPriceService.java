package com.superagent.cost;

import com.superagent.api.provider.ModelPricesDto;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.provider.ModelRouter;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.provider.ProviderService;
import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.tenant.CompanyService;
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

    /** How long a model that failed is passed over before it's tried again. */
    private static final Duration COOL_DOWN = Duration.ofMinutes(10);

    private final ProviderService providers;
    private final SystemSettingStore store;
    private com.superagent.platform.settings.SettingsService settings; // null in tests: no default model
    private org.springframework.context.ApplicationEventPublisher events; // null in tests: nobody told
    // Keyed by company + "/" + model: one company's failing key or relay says nothing about another's.
    private final java.util.Map<String, Instant> failedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> failedWhy = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per company: the default model conversations were last moved off; absent while they run on it. */
    private final java.util.Map<String, String> standingIn = new java.util.concurrent.ConcurrentHashMap<>();
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

    private final CompanyService companies;

    public ModelPriceService(CompanyService companies, ProviderService providers, SystemSettingStore store,
                             @Value("${superagent.cost.catalog-url:https://models.dev/api.json}") String catalogUrl,
                             @Value("${superagent.cost.catalog-check-seconds:600}") long checkSeconds) {
        this.companies = companies;
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

    @Autowired(required = false)
    void setSettings(com.superagent.platform.settings.SettingsService settings) {
        this.settings = settings;
    }

    @Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                java.util.concurrent.atomic.AtomicBoolean unpriced = new java.util.concurrent.atomic.AtomicBoolean();
                companies.forEach(() -> {
                    if (candidates().stream().anyMatch(c -> !c.priced())) {
                        unpriced.set(true);
                    }
                });
                if (due(unpriced.get())) {
                    ModelCatalog fresh = download();
                    if (fresh != null) {
                        companies.forEach(() -> saveFacts(fresh));
                    }
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

    // --- conversations: the default model, or a stand-in nobody has to notice ---

    @Override
    public List<String> chatModels() {
        String def = settings == null ? null : settings.agentDefaultModel();
        List<Candidate> runnable = candidates();
        java.util.function.Predicate<String> usable = id -> runnable.stream().anyMatch(c -> c.id().equals(id)) && !coolingDown(id);
        if (def == null) { // none set: the strongest that can run, then the next down
            List<String> strongest = runnable.stream().sorted(Comparator.comparingDouble(Candidate::blended).reversed())
                    .map(Candidate::id).toList();
            List<String> live = strongest.stream().filter(usable).toList();
            return live.isEmpty() ? strongest : live;
        }
        List<String> out = new java.util.ArrayList<>();
        if (usable.test(def)) {
            out.add(def);
        }
        settings.backupModels().stream().filter(usable).filter(m -> !out.contains(m)).forEach(out::add);
        runnable.stream().sorted(Comparator.comparingDouble(Candidate::blended).reversed()) // strongest first
                .map(Candidate::id).filter(usable).filter(m -> !out.contains(m)).forEach(out::add);
        if (out.isEmpty()) {
            out.add(def); // nothing else either: keep trying the default
        }
        noteStandIn(def, out.get(0));
        return out;
    }

    @Override
    public void reportFailure(String model, String error) {
        if (model == null) {
            return;
        }
        failedUntil.put(failureKey(model), Instant.now().plus(COOL_DOWN));
        failedWhy.put(failureKey(model), error == null ? "" : error.length() > 300 ? error.substring(0, 300) + "…" : error);
        log.warn("model {} failed, passed over for {} min: {}", model, COOL_DOWN.toMinutes(), error);
    }

    private static String failureKey(String model) {
        return TenantContext.require() + "/" + model;
    }

    private boolean coolingDown(String model) {
        String key = failureKey(model);
        Instant until = failedUntil.get(key);
        if (until == null) {
            return false;
        }
        if (until.isBefore(Instant.now())) {
            failedUntil.remove(key); // its time is up: try it again
            return false;
        }
        return true;
    }

    /** Tells the admins once when conversations leave the default model, and once when they're back on it. */
    private synchronized void noteStandIn(String def, String inUse) {
        String company = TenantContext.require();
        String was = standingIn.get(company);
        boolean down = !def.equals(inUse);
        if (down == (was != null) && (!down || def.equals(was))) {
            return;
        }
        if (down) {
            standingIn.put(company, def);
        } else {
            standingIn.remove(company);
        }
        String why = down ? failedWhy.getOrDefault(failureKey(def), "没通过可用性检查") : null;
        log.warn(down ? "default model {} can't run: conversations use {}" : "default model {} works again", def, inUse);
        if (events != null) {
            events.publishEvent(new com.superagent.core.notice.NoticeEvents.DefaultModelChanged(def, inUse, down, why));
        }
    }

    private Optional<ModelCatalog.Fact> fact(String id, Map<String, String> saved) {
        ModelCatalog latest = downloaded;
        Optional<ModelCatalog.Fact> f = latest == null ? Optional.empty() : latest.find(id);
        return f.or(() -> decode(saved.get(FACT + id))).or(() -> bundled.find(id));
    }

    // --- the directory ---

    // The directory itself is platform-wide (one download for every company); the prices saved from it are not.
    private boolean due(boolean unpriced) {
        Instant now = Instant.now();
        if (downloadedAt == null || downloadedAt.plus(FULL_REFRESH).isBefore(now)) {
            return triedAt == null || triedAt.plus(RETRY_UNPRICED).isBefore(now);
        }
        return unpriced && triedAt.plus(RETRY_UNPRICED).isBefore(now);
    }

    /** Downloads the directory and saves the current company's prices from it; a failure keeps what's known. */
    public void refresh() {
        ModelCatalog fresh = download();
        if (fresh != null) {
            saveFacts(fresh);
        }
    }

    /** The fresh directory, or null when it couldn't be read (the error shows on the admin page). */
    private ModelCatalog download() {
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
                ModelCatalog catalog = ModelCatalog.parse(body);
                useCatalog(catalog);
                catalogError = null;
                log.info("model price directory read: {} models", catalog.size());
                return catalog;
            }
        } catch (IOException | RuntimeException e) {
            catalogError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("could not read the model price directory: {}", catalogError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Saves the current company's in-use model prices from {@code catalog}, so a restart without network has them. */
    private void saveFacts(ModelCatalog catalog) {
        Map<String, String> saved = store.all();
        for (Candidate c : candidates()) {
            catalog.find(c.id()).map(ModelPriceService::encode).filter(v -> !v.equals(saved.get(FACT + c.id())))
                    .ifPresent(v -> store.put(FACT + c.id(), v));
        }
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

    /** For tests: as if every failed model's time out were up. */
    void forgetFailures() {
        failedUntil.clear();
        failedWhy.clear();
    }

    /** For tests: the prices of a directory, as if just downloaded. */
    void useCatalog(ModelCatalog catalog) {
        downloaded = catalog;
        downloadedAt = Instant.now();
    }
}
