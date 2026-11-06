package com.superagent.platform.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.superagent.api.provider.CatalogEntryDto;
import com.superagent.api.provider.ProviderDto;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.provider.ModelProvider;
import com.superagent.core.provider.ModelProviderStore;
import com.superagent.core.provider.ProviderCatalog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Manages the model-vendor CLIs the runtime calls. Install/upgrade execute only the
 * command hard-coded in {@link ProviderCatalog} for that entry (never one from a
 * request), as argv without a shell, asynchronously; status and model lists are
 * refreshed by {@link ProviderStatusChecker}.
 *
 * <p>Three refreshes write the same row on different threads (local status, latest
 * version, model list), so every write goes through {@link #update}: re-read the
 * current row and save under one lock, so none overwrites another's columns.
 */
@Service
public class ProviderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);
    private static final TypeReference<List<ProviderModelDto>> MODEL_LIST = new TypeReference<>() { };

    static final String UNSUPPORTED = "unsupported";
    static final String NOT_INSTALLED = "not_installed";
    static final String NO_AUTH = "no_auth";
    static final String OK = "ok";
    static final String NEEDS_UPGRADE = "needs_upgrade";
    static final String INSTALLING = "installing";
    /** An install-only CLI that is on this machine (nothing runs on it yet). */
    static final String INSTALLED = "installed";

    private final ModelProviderStore store;
    private final ProviderProbe probe;
    private final ProviderModelFetcher modelFetcher;
    private final ProviderInferenceProbe inferenceProbe;
    private final NodeRuntimeService nodeRuntime;
    private final CredentialService credentials; // null in tests: everything uses the server's environment
    // Stored model lists carry per-model probe timestamps (Instant).
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Object writeLock = new Object();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();
    /** Source label of the last model fetch per provider; in memory only, refilled by the next fetch. */
    private final Map<String, String> modelsSource = new ConcurrentHashMap<>();
    private final ExecutorService installer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "provider-install");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService lookup = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "provider-lookup");
        t.setDaemon(true);
        return t;
    });

    public ProviderService(ModelProviderStore store, ProviderProbe probe, ProviderModelFetcher modelFetcher,
                           ProviderInferenceProbe inferenceProbe, NodeRuntimeService nodeRuntime,
                           CredentialService credentials) {
        this.store = store;
        this.probe = probe;
        this.modelFetcher = modelFetcher;
        this.inferenceProbe = inferenceProbe;
        this.nodeRuntime = nodeRuntime;
        this.credentials = credentials;
        if (credentials != null) {
            // A switch to another endpoint can change which models answer: re-check them against it.
            credentials.onSwitch("models", key -> store.findByKey(key).ifPresent(p -> lookup.execute(() -> {
                refreshModels(p);
                store.findById(p.id()).ifPresent(this::refreshInference);
            })));
        }
    }

    /** The endpoint/key runs on this CLI use now (null = the server's environment / CLI login). */
    private com.superagent.core.chat.RunCredential currentCredential(String providerKey) {
        return credentials == null ? null : credentials.current(providerKey).credential();
    }

    public List<ProviderDto> list() {
        return store.list().stream().map(this::toDto).toList();
    }

    public ProviderDto detail(String id) {
        return toDto(require(id));
    }

    /** The stored model list for a catalogue key, or empty when that CLI isn't managed / not fetched yet. */
    public List<ProviderModelDto> storedModels(String key) {
        return store.findByKey(key).map(p -> parseModels(p.modelsJson())).orElse(List.of());
    }

    /** Whether the last background probe saw {@code modelId} (under CLI {@code key}) complete an inference. */
    public boolean canInfer(String key, String modelId) {
        return storedModels(key).stream().anyMatch(m -> m.id().equals(modelId) && m.canInfer());
    }

    /**
     * Which managed CLI should run {@code modelId}: one whose probe saw it answer, preferring a CLI
     * that lists it natively (a gateway can list another vendor's model under Claude too).
     */
    public Optional<String> cliFor(String modelId) {
        if (modelId == null) {
            return Optional.empty();
        }
        Optional<String> any = Optional.empty();
        for (ModelProvider p : store.list()) {
            for (ProviderModelDto m : parseModels(p.modelsJson())) {
                if (m.id().equals(modelId) && m.canInfer()) {
                    if (m.nativeModel()) {
                        return Optional.of(p.providerKey());
                    }
                    any = any.or(() -> Optional.of(p.providerKey()));
                }
            }
        }
        return any;
    }

    public List<CatalogEntryDto> catalog() {
        Set<String> added = ConcurrentHashMap.newKeySet();
        store.list().forEach(p -> added.add(p.providerKey()));
        return ProviderCatalog.all().stream()
                .map(e -> new CatalogEntryDto(e.key(), e.name(), e.supported(), e.installable(), e.preview(),
                        added.contains(e.key())))
                .toList();
    }

    public ProviderDto add(String key) {
        ProviderCatalog.Entry entry = ProviderCatalog.find(key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + key));
        if (store.findByKey(key).isPresent()) {
            throw new ProviderAlreadyAddedException(key);
        }
        Instant now = Instant.now();
        ModelProvider p = new ModelProvider(UUID.randomUUID().toString(), key, false, false, null, null,
                entry.installable() ? NOT_INSTALLED : UNSUPPORTED, null, null, null, null, now, now);
        store.save(p);
        ModelProvider refreshed = entry.installable() ? refreshLocal(p) : p;
        if (entry.installable()) {
            // Network lookups off the request thread, so version and models show up without waiting for the slow cadence.
            lookup.execute(() -> {
                refreshLatest(p);
                refreshLocal(p);
                refreshModels(p);
                refreshInference(p); // so a newly added CLI's models get a status without waiting a full cycle
            });
        }
        return toDto(refreshed);
    }

    public void remove(String id) {
        require(id);
        store.delete(id);
        lastError.remove(id);
        modelsSource.remove(id);
    }

    /** Re-fetches the model list now (the detail page's Refresh button). Synchronous so the caller sees the result. */
    public ProviderDto refreshModelsNow(String id) {
        return toDto(refreshModels(require(id)));
    }

    /** "Check for updates": asks the registry for the latest version now, rather than at the hourly sweep. */
    public ProviderDto checkLatest(String id) {
        ModelProvider p = require(id);
        refreshLatest(p);
        return toDto(refreshLocal(require(id)));
    }

    /** The catalogue key of a provider row (the key pool is per CLI). */
    public String keyOf(String id) {
        return require(id).providerKey();
    }

    public ProviderDto install(String id) {
        return runAction(id, false);
    }

    public ProviderDto upgrade(String id) {
        return runAction(id, true);
    }

    private ProviderDto runAction(String id, boolean upgrade) {
        ModelProvider p = require(id);
        ProviderCatalog.Entry entry = entryOf(p);
        String command = upgrade ? entry.upgradeCommand() : entry.installCommand();
        if (command == null) {
            throw new IllegalArgumentException("Install/upgrade is not available for " + entry.name() + " yet");
        }
        if (!busy.add(id)) {
            return toDto(p); // already installing
        }
        installer.execute(() -> {
            lastError.remove(id);
            // Dependencies first: a missing or too-old Node is installed (privately) before the CLI.
            if (nodeProblem(entry) != null) {
                try {
                    String problem = nodeRuntime.ensure(entry.minNode());
                    if (problem != null) {
                        lastError.put(id, problem);
                        finish(id);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    finish(id);
                    return;
                }
            }
            execute(id, entry, command);
        });
        return toDto(p);
    }

    /**
     * Why an npm install can't start: no Node on the search path, or one older than the package requires.
     * Null when it can. Checked up front so the row says "install Node first" instead of a raw npm failure.
     */
    private String nodeProblem(ProviderCatalog.Entry entry) {
        if (entry.minNode() == null) {
            return null;
        }
        Optional<String> node = nodeRuntime.version();
        if (node.isEmpty() || probe.findBinary("npm").isEmpty()) {
            return "需要 Node.js ≥ " + entry.minNode() + "（本机没有 Node.js / npm），请先在上方安装 Node.js";
        }
        if (ProviderProbe.isNewer(entry.minNode(), node.get())) {
            return "需要 Node.js ≥ " + entry.minNode() + "，当前为 " + node.get() + "，请先在上方安装新版 Node.js";
        }
        return null;
    }

    /** Runs a catalogue command as argv. Async: the result surfaces as status, not to a request. */
    private void execute(String id, ProviderCatalog.Entry entry, String command) {
        try {
            String failure = runCommand(entry, command);
            // The system npm can't write its global folder (an apt/yum Node owned by root): rather than asking for
            // sudo, install the private Node and put the CLI beside it.
            if (failure != null && entry.minNode() != null && permissionDenied(failure) && !nodeRuntime.hasPrivate()) {
                log.info("provider {}: system npm can't write its global folder, retrying with a private Node", entry.key());
                String problem = nodeRuntime.installPrivateNow();
                failure = problem != null ? problem : runCommand(entry, command);
            }
            if (failure != null) {
                lastError.put(id, failure);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            finish(id);
        }
    }

    private static boolean permissionDenied(String output) {
        return output.contains("EACCES") || output.contains("EPERM") || output.contains("permission denied");
    }

    /** Clears the busy mark and re-reads the row's state (installed? which version?). */
    private void finish(String id) {
        busy.remove(id);
        store.findById(id).ifPresent(p -> {
            refreshLatest(p);
            refreshLocal(p);
            refreshModels(p);
        });
    }

    /** One run of a catalogue command; null on success, else what went wrong (the output's tail). */
    private String runCommand(ProviderCatalog.Entry entry, String command) throws InterruptedException {
        try {
            List<String> argv = new ArrayList<>(Arrays.asList(command.trim().split("\\s+")));
            Optional<Path> bin = probe.findBinary(argv.get(0));
            bin.ifPresent(b -> argv.set(0, b.toString()));
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            // npm needs node on PATH; a service process often lacks the global bin dirs.
            pb.environment().put("PATH", String.join(File.pathSeparator, probe.searchPath()));
            // The private Node's npm installs beside it — whatever prefix a user/global npmrc names — so the CLI
            // lands on the search path and nothing outside SuperAgent's data dir changes.
            bin.flatMap(nodeRuntime::privatePrefixFor)
                    .ifPresent(prefix -> pb.environment().put("npm_config_prefix", prefix.toString()));
            Process proc = pb.start();
            // Drain while waiting: npm can print enough to fill the pipe and block forever.
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(proc.getInputStream()));
            boolean finished = proc.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
            }
            String out = output.join();
            if (!finished || proc.exitValue() != 0) {
                log.warn("provider {} command failed ({}): {}", entry.key(), command, tail(out));
                return (finished ? "" : "timed out. ") + tail(out);
            }
            log.info("provider {} command succeeded: {}", entry.key(), command);
            return null;
        } catch (IOException e) {
            log.warn("provider {} command could not start: {}", entry.key(), e.getMessage());
            return "could not start: " + e.getMessage();
        }
    }

    // --- refreshes (driven by ProviderStatusChecker) ---

    /**
     * Cheap local probe: binary on PATH, version, auth signal. Skips entries mid-install. An install-only CLI
     * has no auth to check (nothing runs on it): it's just installed, or due an upgrade.
     */
    ModelProvider refreshLocal(ModelProvider p) {
        ProviderCatalog.Entry entry = entryOf(p);
        if (!entry.installable() || busy.contains(p.id())) {
            return p;
        }
        // Probe outside the lock (it runs a process); only the read-modify-write is serialized.
        Optional<Path> bin = probe.findBinary(entry.cli());
        boolean installed = bin.isPresent();
        String version = bin.flatMap(b -> probe.version(b, entry)).orElse(null);
        boolean auth = installed && entry.supported() && probe.looksAuthenticated(entry);
        return update(p, cur -> {
            boolean outdated = ProviderProbe.isNewer(cur.latestVersion(), version);
            String status = !installed ? NOT_INSTALLED
                    : !entry.supported() ? (outdated ? NEEDS_UPGRADE : INSTALLED)
                    : !auth ? NO_AUTH
                    : outdated ? NEEDS_UPGRADE
                    : OK;
            return cur.withStatus(installed, auth, version, cur.latestVersion(), status);
        });
    }

    /** Network probe for the latest published version (slow cadence). */
    ModelProvider refreshLatest(ModelProvider p) {
        ProviderCatalog.Entry entry = entryOf(p);
        if (!entry.installable()) {
            return p;
        }
        Optional<String> latest = probe.latestNpmVersion(entry.npmPackage());
        if (latest.isEmpty()) {
            return p;
        }
        return update(p, cur -> latest.get().equals(cur.latestVersion()) ? cur : cur.withLatestVersion(latest.get()));
    }

    /** Fetches the model list from the CLI / its credentials. A failure keeps the last good list and records why. */
    ModelProvider refreshModels(ModelProvider p) {
        ProviderCatalog.Entry entry = entryOf(p);
        if (!entry.supported()) {
            return p;
        }
        String json;
        String error;
        List<ProviderModelDto> fetched;
        try {
            ProviderModelFetcher.Result result = modelFetcher.fetch(entry.key(), currentCredential(entry.key()));
            fetched = result.models();
            error = null;
            modelsSource.put(p.id(), result.source());
        } catch (IOException e) {
            fetched = null;
            error = e.getMessage();
            log.warn("provider {} model list fetch failed: {}", entry.key(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return p;
        }
        List<ProviderModelDto> newList = fetched;
        String newError = error;
        return update(p, cur -> cur.withModels(
                newList == null ? null : toJson(keepInference(newList, parseModels(cur.modelsJson()))), newError));
    }

    /**
     * Probes every listed model with one real inference and records ok / error per model.
     * Probing runs outside the write lock (each check can take seconds); results are merged
     * by id into whatever list is current at write time, so a concurrent list refresh isn't lost.
     *
     * <p>A check that couldn't reach the API or the CLI's service says nothing about the model, so the model
     * keeps its last status: one network blip must not mark every model failed (and block chat) for an hour.
     * Returns false when some checks were unreachable, so the caller can retry soon.
     */
    boolean refreshInference(ModelProvider p) {
        ProviderCatalog.Entry entry = entryOf(p);
        if (!entry.supported()) {
            return true;
        }
        Map<String, ProviderModelDto> results = new LinkedHashMap<>();
        com.superagent.core.chat.RunCredential credential = currentCredential(entry.key());
        int unreachable = 0;
        String lastUnreachable = null;
        for (ProviderModelDto m : parseModels(p.modelsJson())) {
            try {
                inferenceProbe.check(entry.key(), m.id(), credential);
                results.put(m.id(), m.withInference(ProviderModelDto.INFER_OK, null, Instant.now()));
            } catch (ProbeUnreachableException e) {
                unreachable++;
                lastUnreachable = e.getMessage();
            } catch (IOException e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                results.put(m.id(), m.withInference(ProviderModelDto.INFER_ERROR, reason, Instant.now()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        if (unreachable > 0) {
            log.warn("provider {} inference check: {} models unreachable, kept their last status ({})",
                    entry.key(), unreachable, lastUnreachable);
        }
        if (!results.isEmpty()) {
            long failed = results.values().stream().filter(m -> !m.canInfer()).count();
            log.info("provider {} inference check: {}/{} models ok", entry.key(), results.size() - failed, results.size());
            update(p, cur -> cur.withModelsJson(toJson(keepInference(parseModels(cur.modelsJson()),
                    List.copyOf(results.values())))));
        }
        return unreachable == 0;
    }

    /** {@code models} with each one's probe result carried over from {@code previous} (matched by id). */
    private static List<ProviderModelDto> keepInference(List<ProviderModelDto> models, List<ProviderModelDto> previous) {
        Map<String, ProviderModelDto> byId = new HashMap<>();
        previous.forEach(m -> byId.put(m.id(), m));
        return models.stream().map(m -> {
            ProviderModelDto old = byId.get(m.id());
            return old == null ? m : m.withInference(old.inferStatus(), old.inferError(), old.inferCheckedAt());
        }).toList();
    }

    private String toJson(List<ProviderModelDto> models) {
        try {
            return mapper.writeValueAsString(models);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize model list", e);
        }
    }

    private ModelProvider update(ModelProvider p, UnaryOperator<ModelProvider> change) {
        synchronized (writeLock) {
            ModelProvider cur = store.findById(p.id()).orElse(null);
            if (cur == null) {
                return p; // removed meanwhile
            }
            ModelProvider next = change.apply(cur);
            if (next != cur) {
                store.save(next);
            }
            return next;
        }
    }

    private ModelProvider require(String id) {
        return store.findById(id).orElseThrow(() -> new ProviderNotFoundException(id));
    }

    private static ProviderCatalog.Entry entryOf(ModelProvider p) {
        return ProviderCatalog.find(p.providerKey())
                .orElseThrow(() -> new IllegalStateException("Provider key no longer in catalogue: " + p.providerKey()));
    }

    private List<ProviderModelDto> parseModels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, MODEL_LIST);
        } catch (JsonProcessingException e) {
            return List.of(); // unreadable stored list; the next refresh replaces it
        }
    }

    private ProviderDto toDto(ModelProvider p) {
        ProviderCatalog.Entry e = entryOf(p);
        String status = busy.contains(p.id()) ? INSTALLING : p.status();
        return new ProviderDto(p.id(), e.key(), e.name(), e.cli(), e.supported(), e.installable(), e.preview(),
                e.minNode(), p.installed(), p.canInfer(),
                p.currentVersion(), p.latestVersion(), ProviderProbe.isNewer(p.latestVersion(), p.currentVersion()),
                status, e.installCommand(), e.upgradeCommand(),
                parseModels(p.modelsJson()), modelsSource.get(p.id()), p.modelsError(), p.modelsCheckedAt(),
                lastError.get(p.id()), p.lastCheckedAt(), p.createdAt());
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(String s) {
        return s.length() <= 600 ? s : s.substring(s.length() - 600);
    }

    @PreDestroy
    void shutdown() {
        installer.shutdownNow();
        lookup.shutdownNow();
    }
}
