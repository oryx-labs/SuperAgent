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
import com.superagent.core.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
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
    /** The last install/upgrade per provider row, with its output; in memory, so a restart forgets it. */
    private final Map<String, InstallJob> jobs = new ConcurrentHashMap<>();
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
            // Only when the entry actually changed (the first one seen is startup, which the checker sweeps anyway):
            // a model check is one inference per model.
            Map<String, String> seen = new java.util.concurrent.ConcurrentHashMap<>(); // company + "/" + CLI key
            credentials.onSwitch("models", key -> {
                if (!credentials.hasOwnKey(key)) {
                    return; // its last key was removed: nothing to check models with
                }
                String now = credentials.current(key).id();
                String before = seen.put(TenantContext.require() + "/" + key, now);
                // The first event per CLI is usually startup, except a CLI's first key: its list is still empty.
                boolean firstKey = before == null && store.findByKey(key)
                        .map(p -> parseModels(p.modelsJson()).isEmpty()).orElse(false);
                if (!firstKey && (before == null || before.equals(now))) {
                    return;
                }
                store.findByKey(key).ifPresent(p -> lookup.execute(TenantContext.wrap(() -> {
                    refreshModels(p);
                    store.findById(p.id()).ifPresent(x -> refreshInference(x, true)); // another endpoint: check them all
                })));
            });
        }
    }

    /**
     * Whether this company can authenticate this CLI with a key of its own. A status check: never throws (another
     * company with no key yet is simply "no auth", not an error).
     */
    private boolean hasKey(String providerKey) {
        return credentials != null && credentials.hasOwnKey(providerKey);
    }

    /** Without a key store (tests), the machine's own CLI login stands in. */
    private boolean machineLogin(ProviderCatalog.Entry entry) {
        return credentials == null && probe.looksAuthenticated(entry);
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
            lookup.execute(TenantContext.wrap(() -> {
                refreshLatest(p);
                refreshLocal(p);
                refreshModels(p);
                refreshInference(p, true); // so a newly added CLI's models get a status without waiting a full cycle
            }));
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
    /** The list now; whether each model answers follows in the background (one inference per model). */
    public ProviderDto refreshModelsNow(String id) {
        ModelProvider refreshed = refreshModels(require(id));
        lookup.execute(TenantContext.wrap(() -> store.findById(id).ifPresent(x -> refreshInference(x, MANUAL_RECHECK_AFTER))));
        return toDto(refreshed);
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
        // One install at a time (npm's global folder isn't safe to write concurrently): a second one waits.
        InstallJob job = new InstallJob(upgrade ? "upgrade" : "install", "排队中：等前一个安装结束");
        jobs.put(id, job);
        installer.execute(TenantContext.wrap(() -> {
            lastError.remove(id);
            log.info("provider {}: {} started", entry.key(), upgrade ? "upgrade" : "install");
            // Dependencies first: a missing or too-old Node is installed (privately) before the CLI.
            if (nodeProblem(entry) != null) {
                try {
                    String problem = nodeRuntime.ensure(entry.minNode(), job);
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
            execute(id, entry, command, job);
        }));
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
    private void execute(String id, ProviderCatalog.Entry entry, String command, InstallJob job) {
        try {
            String failure = runCommand(entry, command, job);
            // The system npm can't write its global folder (an apt/yum Node owned by root): rather than asking for
            // sudo, install the private Node and put the CLI beside it.
            if (failure != null && entry.minNode() != null && permissionDenied(failure) && !nodeRuntime.hasPrivate()) {
                log.info("provider {}: system npm can't write its global folder, retrying with a private Node", entry.key());
                job.step("系统 npm 没有写权限，改为安装 SuperAgent 自带的 Node.js 后重试");
                String problem = nodeRuntime.installPrivateNow(job);
                failure = problem != null ? problem : runCommand(entry, command, job);
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
        InstallJob job = jobs.get(id);
        if (job != null && job.running()) {
            job.finish(lastError.get(id));
        }
        busy.remove(id);
        store.findById(id).ifPresent(p -> {
            refreshLatest(p);
            refreshLocal(p);
            refreshModels(p);
        });
    }

    /**
     * One run of a catalogue command, its output streamed into the job line by line; null on success, else what
     * went wrong (the output's tail). npm runs at http log level so a slow download still shows progress, and a run
     * that prints nothing for {@link #QUIET} is stopped: npm retries an unreachable registry for a very long time.
     */
    private String runCommand(ProviderCatalog.Entry entry, String command, InstallJob job) throws InterruptedException {
        try {
            List<String> argv = new ArrayList<>(Arrays.asList(command.trim().split("\\s+")));
            boolean npm = "npm".equals(argv.get(0));
            Optional<Path> bin = probe.findBinary(argv.get(0));
            bin.ifPresent(b -> argv.set(0, b.toString()));
            if (npm) {
                argv.add("--loglevel=http");
            }
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            // npm needs node on PATH; a service process often lacks the global bin dirs.
            pb.environment().put("PATH", String.join(File.pathSeparator, probe.searchPath()));
            // The private Node's npm installs beside it — whatever prefix a user/global npmrc names — so the CLI
            // lands on the search path and nothing outside SuperAgent's data dir changes.
            bin.flatMap(nodeRuntime::privatePrefixFor)
                    .ifPresent(prefix -> pb.environment().put("npm_config_prefix", prefix.toString()));
            job.step("运行 " + String.join(" ", argv));
            Process proc = pb.start();
            java.util.concurrent.atomic.AtomicLong lastOutputAt = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
            StringBuilder out = new StringBuilder();
            // Drain while waiting: npm can print enough to fill the pipe and block forever.
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        lastOutputAt.set(System.nanoTime());
                        String clean = line.replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "").stripTrailing();
                        if (!clean.isBlank()) {
                            job.line(clean);
                            synchronized (out) {
                                out.append(clean).append('\n');
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process gone
                }
            });
            long deadline = System.nanoTime() + TOTAL.toNanos();
            String stopped = null;
            while (!proc.waitFor(1, TimeUnit.SECONDS)) {
                if (System.nanoTime() - lastOutputAt.get() > QUIET.toNanos()) {
                    stopped = QUIET.toMinutes() + " 分钟没有任何输出，已停止。多半是连不上 npm 源"
                            + (npm ? "（默认 registry.npmjs.org，配了镜像则是镜像地址）" : "") + "，请检查服务器网络或代理";
                } else if (System.nanoTime() > deadline) {
                    stopped = "超过 " + TOTAL.toMinutes() + " 分钟仍未完成，已停止";
                }
                if (stopped != null) {
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                    proc.waitFor(10, TimeUnit.SECONDS);
                    break;
                }
            }
            reader.join(5_000);
            String output;
            synchronized (out) {
                output = out.toString();
            }
            if (stopped != null || proc.exitValue() != 0) {
                String why = stopped != null ? stopped : InstallFailures.summarize(output, proc.exitValue());
                log.warn("provider {} command failed ({}): {}", entry.key(), command, why);
                return why;
            }
            log.info("provider {} command succeeded: {}", entry.key(), command);
            return null;
        } catch (IOException e) {
            log.warn("provider {} command could not start: {}", entry.key(), e.getMessage());
            return "无法启动：" + e.getMessage();
        }
    }

    private static final java.time.Duration QUIET = java.time.Duration.ofMinutes(3);
    private static final java.time.Duration TOTAL = java.time.Duration.ofMinutes(30);

    /** The last install/upgrade of a row, with its output; null when there's been none since the server started. */
    public com.superagent.api.provider.InstallLogDto installLog(String id) {
        require(id);
        InstallJob job = jobs.get(id);
        return job == null ? null : job.toDto();
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
        boolean auth = installed && entry.supported() && (hasKey(entry.key()) || machineLogin(entry));
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
        // A company with no key of its own (and no right to the machine's login) has nothing to list models with yet.
        if (credentials != null && !credentials.hasOwnKey(entry.key())) {
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
    /** The scheduled sweep: models checked within the last {@link #RECHECK_AFTER} keep their result. */
    boolean refreshInference(ModelProvider p) {
        return refreshInference(p, RECHECK_AFTER);
    }

    /** {@code all}: every model (another endpoint, a new CLI); else as the scheduled sweep. */
    boolean refreshInference(ModelProvider p, boolean all) {
        return refreshInference(p, all ? java.time.Duration.ZERO : RECHECK_AFTER);
    }

    /** The Refresh button: a model checked in the last minute keeps its result, so clicking again re-checks nothing. */
    static final java.time.Duration MANUAL_RECHECK_AFTER = java.time.Duration.ofMinutes(1);

    /** A model checked this recently isn't checked again by the scheduled sweep (a restart included). */
    static final java.time.Duration RECHECK_AFTER = java.time.Duration.ofMinutes(50);
    private static final int PARALLEL_CHECKS = 6;
    /** Rounds running now, by provider id, and since when: one at a time per CLI, however they're asked for. */
    private final Map<String, Instant> checking = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService checks = Executors.newFixedThreadPool(PARALLEL_CHECKS, r -> {
        Thread t = new Thread(r, "provider-check");
        t.setDaemon(true);
        return t;
    });

    /**
     * Checks that each listed model answers, in parallel: those not checked within {@code freshEnough}. A round
     * already running for this CLI isn't started twice.
     */
    boolean refreshInference(ModelProvider p, java.time.Duration freshEnough) {
        ProviderCatalog.Entry entry = entryOf(p);
        if (!entry.supported()) {
            return true;
        }
        Instant started = Instant.now();
        if (checking.putIfAbsent(p.id(), started) != null) {
            log.info("provider {}: a model check is already running, not starting another", entry.key());
            return true;
        }
        try {
            return sweep(p, entry, freshEnough, started);
        } finally {
            checking.remove(p.id());
        }
    }

    private boolean sweep(ModelProvider p, ProviderCatalog.Entry entry, java.time.Duration freshEnough, Instant started) {
        if (credentials != null && !credentials.hasOwnKey(entry.key())) {
            return true; // no key of the company's own: nothing to test its models with (never the machine's login)
        }
        Map<String, ProviderModelDto> results = new java.util.concurrent.ConcurrentHashMap<>();
        com.superagent.core.chat.RunCredential credential = currentCredential(entry.key());
        java.util.concurrent.atomic.AtomicInteger unreachableCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> lastUnreachableRef = new java.util.concurrent.atomic.AtomicReference<>();
        List<ProviderModelDto> due = parseModels(p.modelsJson()).stream().filter(m -> m.inferCheckedAt() == null
                || !m.inferCheckedAt().plus(freshEnough).isAfter(started)).toList();
        if (due.isEmpty()) {
            return true;
        }
        log.info("provider {}: checking {} model(s)", entry.key(), due.size());
        String company = TenantContext.require(); // the checks book their tokens to this company
        List<java.util.concurrent.Callable<Void>> jobs = due.stream().<java.util.concurrent.Callable<Void>>map(m -> () -> TenantContext.callChecked(company, () -> {
            try {
                inferenceProbe.check(entry.key(), m.id(), credential);
                results.put(m.id(), m.withInference(ProviderModelDto.INFER_OK, null, Instant.now()));
            } catch (ProbeUnreachableException e) {
                unreachableCount.incrementAndGet();
                lastUnreachableRef.set(e.getMessage());
            } catch (IOException e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                results.put(m.id(), m.withInference(ProviderModelDto.INFER_ERROR, reason, Instant.now()));
            }
            return null;
        })).toList();
        try {
            checks.invokeAll(jobs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        int unreachable = unreachableCount.get();
        String lastUnreachable = lastUnreachableRef.get();
        if (unreachable > 0) {
            log.warn("provider {} inference check: {} models unreachable, kept their last status ({})",
                    entry.key(), unreachable, lastUnreachable);
        }
        if (!results.isEmpty()) {
            long failed = results.values().stream().filter(m -> !m.canInfer()).count();
            log.info("provider {} inference check: {}/{} models ok", entry.key(), results.size() - failed, results.size());
            update(p, cur -> cur.withModelsJson(toJson(keepInference(parseModels(cur.modelsJson()),
                    mergeChecked(parseModels(cur.modelsJson()), results)))));
        }
        return unreachable == 0;
    }

    /** The models' last results with this round's on top (a partial round keeps the others' results). */
    private static List<ProviderModelDto> mergeChecked(List<ProviderModelDto> current, Map<String, ProviderModelDto> fresh) {
        return current.stream().map(m -> fresh.getOrDefault(m.id(), m)).toList();
    }

    private com.superagent.core.usage.UsageStore usageStore; // null in tests: no figure shown

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsageStore(com.superagent.core.usage.UsageStore usageStore) {
        this.usageStore = usageStore;
    }

    /** What this CLI's background checks used in the last day. */
    private long checkTokens24h(String providerKey) {
        if (usageStore == null) {
            return 0;
        }
        try {
            return usageStore.between(ProviderInferenceProbe.SYSTEM_USER, Instant.now().minus(java.time.Duration.ofDays(1)), Instant.now())
                    .stream().filter(r -> providerKey.equals(r.cli())).mapToLong(com.superagent.core.usage.UsageRecord::total).sum();
        } catch (RuntimeException e) {
            return 0;
        }
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
        InstallJob job = jobs.get(p.id());
        return new ProviderDto(p.id(), e.key(), e.name(), e.cli(), e.supported(), e.installable(), e.preview(),
                e.minNode(), p.installed(), p.canInfer(),
                p.currentVersion(), p.latestVersion(), ProviderProbe.isNewer(p.latestVersion(), p.currentVersion()),
                status, e.installCommand(), e.upgradeCommand(),
                parseModels(p.modelsJson()), modelsSource.get(p.id()), p.modelsError(), p.modelsCheckedAt(),
                lastError.get(p.id()), job == null ? null : job.currentStep(), job != null,
                p.lastCheckedAt(), p.createdAt(), checking.get(p.id()), checkTokens24h(e.key()));
    }


    private static String tail(String s) {
        return s.length() <= 600 ? s : s.substring(s.length() - 600);
    }

    @PreDestroy
    void shutdown() {
        installer.shutdownNow();
        lookup.shutdownNow();
        checks.shutdownNow();
    }
}
