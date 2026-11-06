package com.superagent.platform.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.api.provider.CredentialDto;
import com.superagent.api.provider.CredentialRequests;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.chat.RunCredential;
import com.superagent.core.provider.ModelProvider;
import com.superagent.core.provider.ModelProviderStore;
import com.superagent.core.provider.ProviderCredential;
import com.superagent.core.provider.ProviderCredentialStore;
import com.superagent.core.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Each runtime CLI's pool of endpoints + keys, and the choice of which one a run uses: the highest-priority
 * entry not known to be failing, with the server's own environment / CLI login as the last resort. When a run
 * fails in a way another key can fix ({@link EndpointFailures}), the entry it used is marked failing and the next
 * one is handed back for a retry; a periodic check re-tests every entry, so a recovered higher-priority key takes
 * over again. Keys are stored encrypted and never leave the server — the page only sees a masked hint.
 */
@Service
public class CredentialService {

    /** Every installable CLI has a pool; failover and checks apply to those whose protocol we can speak. */
    public static final Set<String> POOLED = com.superagent.core.provider.ProviderCatalog.all().stream()
            .filter(com.superagent.core.provider.ProviderCatalog.Entry::installable)
            .map(com.superagent.core.provider.ProviderCatalog.Entry::key).collect(java.util.stream.Collectors.toSet());
    public static final String ENV_ID = "env";

    private static final Set<String> VENDOR_CHECKED = Set.of("deepseek", "glm", "kimi", "qwen");
    private static final Set<String> CHECKED = Set.of("claude", "codex", "deepseek", "glm", "kimi", "qwen");

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);
    private static final TypeReference<List<ProviderModelDto>> MODEL_LIST = new TypeReference<>() {
    };

    private final ProviderCredentialStore store;
    private final ModelProviderStore providers;
    private final SecretCipher cipher;
    private final ProviderInferenceProbe probe;
    private final MachineCredentials machine; // null in tests: nothing to import
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService checker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "credential-check");
        t.setDaemon(true);
        return t;
    });
    /** Told when a provider's choice changes, so its model checks re-run against the new endpoint. */
    private final Map<String, Consumer<String>> onSwitch = new ConcurrentHashMap<>();

    /** Which entry a run uses: {@code credential} null = the server's environment / CLI login. */
    public record Choice(RunCredential credential) {
        public String id() {
            return credential == null ? ENV_ID : credential.id();
        }
    }

    public CredentialService(ProviderCredentialStore store, ModelProviderStore providers, SecretCipher cipher,
                             ProviderInferenceProbe probe, MachineCredentials machine) {
        this.store = store;
        this.providers = providers;
        this.cipher = cipher;
        this.probe = probe;
        this.machine = machine;
    }

    /**
     * Startup: bring what the machine already has into the console (so it's managed in one place), then tell the
     * listeners — the CLI config writer among them — which entry each pool uses.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void onReady() {
        for (String key : POOLED) {
            try {
                importMachine(key);
            } catch (RuntimeException e) {
                log.warn("{}: could not import the machine's credentials: {}", key, e.getMessage());
            }
            notifySwitch(key);
        }
    }

    /**
     * Adds the machine's keys not in the pool yet — never one an admin deleted (deleted entries keep their hash).
     * Into an empty pool they go first, so runs keep using what they used before; otherwise last.
     */
    public int importMachine(String providerKey) {
        backfillHashes(providerKey);
        java.util.Set<String> known = store.knownKeyHashes(providerKey);
        int added = 0;
        for (MachineCredentials.Found f : machine == null ? List.<MachineCredentials.Found>of() : machine.find(providerKey)) {
            String hash = ProviderCredential.hash(f.apiKey());
            if (!known.add(hash)) {
                continue;
            }
            int next = store.list(providerKey).stream().mapToInt(ProviderCredential::priority).max().orElse(0) + 1;
            Instant now = Instant.now();
            store.save(new ProviderCredential(UUID.randomUUID().toString(), providerKey, label("本机：" + f.where(), next),
                    f.baseUrl(), cipher.encrypt(f.apiKey()), f.bearer(), next, true, ProviderCredential.UNKNOWN, null,
                    null, now, now, ProviderCredential.MACHINE, hash));
            added++;
            log.info("{}: imported the machine's key from {}", providerKey, f.where());
        }
        if (added > 0) {
            store.list(providerKey).stream().filter(c -> ProviderCredential.MACHINE.equals(c.source()))
                    .forEach(c -> checkSoon(c.id()));
        }
        return added;
    }

    /** Entries saved before hashes existed get one, so their key is recognised when found again on the machine. */
    private void backfillHashes(String providerKey) {
        for (ProviderCredential c : store.list(providerKey)) {
            if (c.keyHash() == null) {
                try {
                    store.save(new ProviderCredential(c.id(), c.providerKey(), c.label(), c.baseUrl(), c.keyCipher(),
                            c.bearer(), c.priority(), c.enabled(), c.status(), c.lastError(), c.checkedAt(),
                            c.createdAt(), c.updatedAt(), c.source(), ProviderCredential.hash(cipher.decrypt(c.keyCipher()))));
                } catch (IllegalStateException e) {
                    // can't decrypt (secret key changed): leave it; the page already shows it as unreadable
                }
            }
        }
    }

    void onSwitch(String listenerKey, Consumer<String> listener) {
        onSwitch.put(listenerKey, listener);
    }

    // --- choosing ---

    /** What runs on {@code providerKey} use now. */
    public Choice current(String providerKey) {
        return choose(providerKey, null);
    }

    /**
     * After a run on {@code used} failed with {@code error}: if another key could fix it, mark {@code used}
     * failing and return the next entry to retry with. Empty when the error isn't about the endpoint/key, or
     * there's nothing left to try.
     */
    public Optional<Choice> failover(String providerKey, Choice used, String error) {
        if (!POOLED.contains(providerKey) || !EndpointFailures.isEndpointFailure(error)) {
            return Optional.empty();
        }
        if (used.credential() != null) {
            store.find(used.id()).ifPresent(c -> store.save(c.withHealth(ProviderCredential.ERROR, tail(error))));
        }
        Choice next = choose(providerKey, used.id());
        if (next.id().equals(used.id())) {
            return Optional.empty();
        }
        log.warn("{}: endpoint {} failed ({}), switching to {}", providerKey, used.id(), tail(error), next.id());
        notifySwitch(providerKey);
        return Optional.of(next);
    }

    private Choice choose(String providerKey, String excluding) {
        if (!POOLED.contains(providerKey)) {
            return new Choice(null);
        }
        List<ProviderCredential> pool = store.list(providerKey).stream().filter(ProviderCredential::enabled)
                .filter(c -> !c.id().equals(excluding)).toList();
        Optional<ProviderCredential> healthy = pool.stream().filter(ProviderCredential::usable).findFirst();
        if (healthy.isPresent()) {
            return new Choice(toRun(healthy.get()));
        }
        // Every page key is failing: the server's own environment / login is the last resort, unless that
        // is what just failed — then the best page key, which may have recovered since it was marked.
        if (!ENV_ID.equals(excluding) || pool.isEmpty()) {
            return new Choice(null);
        }
        return new Choice(toRun(pool.get(0)));
    }

    /**
     * The vendor's official endpoint, from the catalogue. An entry without its own endpoint is always sent there
     * explicitly: left unset, a CLI would fall back to whatever the server's own CLI config points at (a relay, say).
     */
    private static String defaultBaseUrl(String providerKey) {
        return com.superagent.core.provider.ProviderCatalog.find(providerKey)
                .map(e -> e.access().defaultBaseUrl()).orElse(null);
    }

    private RunCredential toRun(ProviderCredential c) {
        return new RunCredential(c.id(), c.baseUrl() != null ? c.baseUrl() : defaultBaseUrl(c.providerKey()),
                cipher.decrypt(c.keyCipher()), c.bearer());
    }

    // --- checking ---

    /** Re-tests every entry of every pool (the periodic sweep): failing ones come back once they answer. */
    public void checkAll() {
        for (String key : POOLED) {
            String before = current(key).id();
            store.list(key).stream().filter(ProviderCredential::enabled).forEach(this::check);
            if (!current(key).id().equals(before)) {
                log.info("{}: now using {} (was {})", key, current(key).id(), before);
                notifySwitch(key);
            }
        }
    }

    /** One real inference through the entry, on a model the CLI is known to run. */
    ProviderCredential check(ProviderCredential c) {
        String model = testModel(c.providerKey());
        ProviderCredential checked;
        boolean vendor = VENDOR_CHECKED.contains(c.providerKey()); // these can check a key without a model
        if (model == null && !vendor) {
            checked = c.withHealth(ProviderCredential.UNKNOWN, CHECKED.contains(c.providerKey())
                    ? "还没有可用来测试的模型（等模型列表刷新后再检查）" : "这个 CLI 暂不支持检查 Key");
        } else {
            try {
                probe.check(c.providerKey(), model, toRun(c));
                checked = c.withHealth(ProviderCredential.OK, null);
            } catch (IOException e) {
                checked = c.withHealth(ProviderCredential.ERROR, tail(e.getMessage() == null ? e.toString() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return c;
            } catch (IllegalStateException e) { // key can't be decrypted (secret key changed)
                checked = c.withHealth(ProviderCredential.ERROR, "密钥无法解密，请重新填写 API Key");
            }
        }
        store.save(checked);
        return checked;
    }

    /** The model a health check asks: one the last model sweep saw answer, else the first listed. */
    private String testModel(String providerKey) {
        List<ProviderModelDto> models = providers.findByKey(providerKey).map(ModelProvider::modelsJson)
                .map(this::parse).orElse(List.of());
        return models.stream().filter(ProviderModelDto::canInfer).map(ProviderModelDto::id).findFirst()
                .orElse(models.isEmpty() ? null : models.get(0).id());
    }

    private void checkSoon(String id) {
        checker.execute(() -> store.find(id).ifPresent(c -> {
            String before = current(c.providerKey()).id();
            check(c);
            if (!current(c.providerKey()).id().equals(before)) {
                notifySwitch(c.providerKey());
            }
        }));
    }

    public CredentialDto checkNow(String providerKey, String id) {
        return toDto(check(require(providerKey, id)), current(providerKey).id());
    }

    private void notifySwitch(String providerKey) {
        onSwitch.values().forEach(l -> {
            try {
                l.accept(providerKey);
            } catch (RuntimeException e) {
                log.warn("switch listener failed for {}: {}", providerKey, e.getMessage());
            }
        });
    }

    // --- the page ---

    public List<CredentialDto> list(String providerKey) {
        requirePooled(providerKey);
        String current = current(providerKey).id();
        List<CredentialDto> out = new ArrayList<>(store.list(providerKey).stream().map(c -> toDto(c, current)).toList());
        // The machine's keys are entries above; what's left on the machine is the CLI's own login (a subscription).
        out.add(new CredentialDto(ENV_ID, null, null, null, false, Integer.MAX_VALUE, true,
                ProviderCredential.UNKNOWN, null, null, ENV_ID.equals(current), true, ProviderCredential.MACHINE,
                defaultBaseUrl(providerKey)));
        return out;
    }

    public CredentialDto add(String providerKey, CredentialRequests.Save req) {
        requirePooled(providerKey);
        String key = req.apiKey() == null ? "" : req.apiKey().strip();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        String hash = ProviderCredential.hash(key);
        if (store.list(providerKey).stream().anyMatch(c -> hash.equals(c.keyHash()))) {
            throw new IllegalArgumentException("这个 API Key 已经在列表里了");
        }
        int next = store.list(providerKey).stream().mapToInt(ProviderCredential::priority).max().orElse(0) + 1;
        Instant now = Instant.now();
        ProviderCredential c = new ProviderCredential(UUID.randomUUID().toString(), providerKey, label(req.label(), next),
                baseUrl(req.baseUrl()), cipher.encrypt(key), bearer(providerKey, req.bearer()), next,
                req.enabled() == null || req.enabled(), ProviderCredential.UNKNOWN, null, null, now, now,
                ProviderCredential.CONSOLE, hash);
        store.save(c);
        notifySwitch(providerKey); // the CLI config follows if this is now the entry runs use
        checkSoon(c.id());
        return toDto(c, current(providerKey).id());
    }

    public CredentialDto update(String providerKey, String id, CredentialRequests.Save req) {
        ProviderCredential c = require(providerKey, id);
        String key = req.apiKey() == null ? "" : req.apiKey().strip();
        ProviderCredential updated = new ProviderCredential(c.id(), providerKey,
                req.label() == null ? c.label() : label(req.label(), c.priority()),
                req.baseUrl() == null ? c.baseUrl() : baseUrl(req.baseUrl()),
                key.isEmpty() ? c.keyCipher() : cipher.encrypt(key),
                req.bearer() == null ? c.bearer() : bearer(providerKey, req.bearer()), c.priority(),
                req.enabled() == null ? c.enabled() : req.enabled(),
                ProviderCredential.UNKNOWN, null, null, c.createdAt(), Instant.now(), c.source(),
                key.isEmpty() ? c.keyHash() : ProviderCredential.hash(key));
        store.save(updated);
        notifySwitch(providerKey);
        checkSoon(id);
        return toDto(updated, current(providerKey).id());
    }

    public void delete(String providerKey, String id) {
        require(providerKey, id);
        store.delete(id);
        notifySwitch(providerKey);
    }

    public List<CredentialDto> reorder(String providerKey, List<String> ids) {
        requirePooled(providerKey);
        Map<String, ProviderCredential> byId = new java.util.HashMap<>();
        store.list(providerKey).forEach(c -> byId.put(c.id(), c));
        int priority = 1;
        for (String id : ids) {
            ProviderCredential c = byId.remove(id);
            if (c != null) {
                store.save(c.withPriority(priority++));
            }
        }
        for (ProviderCredential rest : byId.values()) { // ids the request left out keep their relative order, last
            store.save(rest.withPriority(priority++));
        }
        notifySwitch(providerKey);
        return list(providerKey);
    }

    private ProviderCredential require(String providerKey, String id) {
        requirePooled(providerKey);
        return store.find(id).filter(c -> c.providerKey().equals(providerKey))
                .orElseThrow(() -> new IllegalArgumentException("没有这个 API Key：" + id));
    }

    private static void requirePooled(String providerKey) {
        if (!POOLED.contains(providerKey)) {
            throw new IllegalArgumentException("这个 CLI 不能配置 API Key：" + providerKey);
        }
    }

    private CredentialDto toDto(ProviderCredential c, String currentId) {
        String hint;
        try {
            hint = mask(cipher.decrypt(c.keyCipher()));
        } catch (IllegalStateException e) {
            hint = "（无法解密）";
        }
        return new CredentialDto(c.id(), c.label(), c.baseUrl(), hint, c.bearer(), c.priority(), c.enabled(),
                c.status(), c.lastError(), c.checkedAt(), c.id().equals(currentId), false, c.source(),
                defaultBaseUrl(c.providerKey()));
    }

    /** Enough to recognise a key, never enough to use it. */
    static String mask(String key) {
        if (key == null || key.length() < 12) {
            return "••••";
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    private static String label(String label, int n) {
        String l = label == null ? "" : label.strip();
        return l.isEmpty() ? "Key " + n : (l.length() <= 100 ? l : l.substring(0, 100));
    }

    /** A plain http(s) URL or nothing (the vendor's default). */
    static String baseUrl(String url) {
        String u = url == null ? "" : url.strip().replaceAll("/+$", "");
        if (u.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("访问地址格式不对：" + u);
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("访问地址要以 http:// 或 https:// 开头：" + u);
        }
        return u;
    }

    /** For Claude the page chooses (relays usually want a Bearer token); GLM wants one; the rest take a plain key. */
    private static boolean bearer(String providerKey, Boolean requested) {
        return "glm".equals(providerKey) || ("claude".equals(providerKey) && (requested == null || requested));
    }

    private List<ProviderModelDto> parse(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : mapper.readValue(json, MODEL_LIST);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String tail(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() <= 300 ? t : "…" + t.substring(t.length() - 300);
    }
}
