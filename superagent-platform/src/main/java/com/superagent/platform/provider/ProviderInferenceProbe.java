package com.superagent.platform.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.superagent.core.chat.RunCredential;
import com.superagent.core.settings.SystemSettingStore;

/**
 * Checks whether a listed model can actually complete an inference — a model list only says
 * a model exists, not that this account/gateway can run it. Each check is the cheapest real
 * call that exercises the same credentials the runtime uses:
 * <ul>
 *   <li><b>claude</b> with an API key / auth token: one {@code POST /v1/messages} with a tiny
 *       {@code max_tokens}, against the same base URL Claude Code uses.</li>
 *   <li><b>codex</b> with an API key: one {@code POST /responses} capped at a few output tokens, against the
 *       credential's own endpoint (a full Codex run costs ~7,000 input tokens of system prompt; this costs ~10).</li>
 *   <li><b>claude</b> on a subscription login, and <b>codex</b> on a ChatGPT login: a one-shot CLI run, since there
 *       is no key to call the API with. These cost a full CLI turn.</li>
 * </ul>
 * Every check's tokens are kept, on "system" (they belong to no one), so the background's cost shows.
 */
@Component
public class ProviderInferenceProbe {

    // Any reply counts as success — the content is never inspected.
    /** Asks for one character: a CLI with no cap on the reply would otherwise answer a greeting at length. */
    private static final String PROMPT = "只回复数字 1";
    private static final int CLI_TIMEOUT_SECONDS = 90;

    private final ProviderProbe probe;
    private static final ObjectMapper mapper = new ObjectMapper();
    // HTTP/1.1: a local relay may swallow the default client's h2c upgrade and never reply (see ProviderModelFetcher).
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** Who background checks are booked to in the usage records. */
    public static final String SYSTEM_USER = "system";
    public static final String SOURCE_MODEL_CHECK = "model_check";
    public static final String SOURCE_KEY_CHECK = "key_check";

    /** Looked up when a check is booked: the recorder needs prices, which need the providers this probe serves. */
    private org.springframework.beans.factory.ObjectProvider<com.superagent.core.usage.UsageRecorder> usage; // null in tests

    public ProviderInferenceProbe(ProviderProbe probe) {
        this.probe = probe;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsageRecorder(org.springframework.beans.factory.ObjectProvider<com.superagent.core.usage.UsageRecorder> usage) {
        this.usage = usage;
    }

    /** What this check cost, on "system". */
    private void book(String providerKey, String source, com.superagent.core.usage.TokenUsage used) {
        com.superagent.core.usage.UsageRecorder recorder = usage == null ? null : usage.getIfAvailable();
        if (recorder == null || used == null || used.nothingUsed()) {
            return;
        }
        try {
            recorder.record(new com.superagent.core.usage.UsageContext(SYSTEM_USER, source, null, null, null, providerKey),
                    mapper.writeValueAsString(List.of(used)));
        } catch (IOException | RuntimeException e) {
            // keeping the record is best effort: the check itself stands
        }
    }

    /**
     * Whether a pool credential works at all, as cheaply as can be: its endpoint's model list (no tokens) when it has
     * one, else one tiny inference on {@code modelId}.
     */
    public void checkKey(String providerKey, String modelId, RunCredential credential) throws IOException, InterruptedException {
        if ("codebuddy".equals(providerKey) || "gemini".equals(providerKey) || "minimax".equals(providerKey)) {
            // No fixed OpenAI /models URL: a short headless turn is the key check (version alone is not auth).
            check(providerKey, modelId, credential, SOURCE_KEY_CHECK);
            return;
        }
        var entry = com.superagent.core.provider.ProviderCatalog.find(providerKey).orElseThrow();
        java.util.Optional<ApiAccess> access = "claude".equals(providerKey) ? ApiAccess.anthropic(credential)
                : ApiAccess.of(entry, credential);
        if (access.isPresent() && !"claude".equals(providerKey) && !"glm".equals(providerKey)
                && access.get().base() != null) {
            ApiAccess a = access.get();
            HttpResponse<String> resp = sendRaw(a.base(), a.authorizeBearer(HttpRequest.newBuilder(URI.create(a.modelsUrl(providerKey)))
                    .timeout(Duration.ofSeconds(30)).GET()));
            if (resp.statusCode() == 200) {
                return;
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new IOException(cause(resp.statusCode(), resp.body()) + "（HTTP " + resp.statusCode() + errorDetail(resp.body()) + "）");
            }
            // No model list there (some relays): fall through to one tiny inference.
        }
        if (modelId == null && !VENDORS.contains(providerKey)) {
            throw new IOException("还没有可用来测试的模型（等模型列表刷新后再检查）");
        }
        check(providerKey, modelId, credential, SOURCE_KEY_CHECK);
    }

    private static final java.util.Set<String> VENDORS = java.util.Set.of("deepseek", "glm", "kimi", "qwen", "grok",
            "codebuddy", "gemini", "minimax", "qoder");

    private SystemSettingStore settings; // null in tests: CodeBuddy region defaults to internal

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(SystemSettingStore settings) {
        this.settings = settings;
    }

    /** Runs one inference against {@code modelId}; returns normally on success, throws with a readable reason. */
    public void check(String providerKey, String modelId) throws IOException, InterruptedException {
        check(providerKey, modelId, null);
    }

    /** The same check through a specific pool credential (null = the server's environment / CLI login). */
    public void check(String providerKey, String modelId, RunCredential credential) throws IOException, InterruptedException {
        check(providerKey, modelId, credential, SOURCE_MODEL_CHECK);
    }

    private void check(String providerKey, String modelId, RunCredential credential, String source)
            throws IOException, InterruptedException {
        com.superagent.core.usage.TokenUsage used = switch (providerKey) {
            case "claude" -> checkClaude(modelId, credential);
            case "codex" -> checkCodex(modelId, credential);
            case "codebuddy" -> checkCodeBuddy(modelId, credential);
            case "gemini" -> checkGemini(modelId, credential);
            case "minimax" -> checkMinimax(modelId, credential);
            case "qoder" -> checkQoder(modelId, credential);
            case "deepseek", "glm", "kimi", "qwen", "grok" -> checkVendor(providerKey, modelId, credential);
            default -> throw new IOException("Inference check is not available for " + providerKey + " yet");
        };
        book(providerKey, source, used);
    }

    /**
     * CodeBuddy: one short {@code -p} turn that exercises the key. {@code --version} alone is not auth —
     * without a model the caller must leave health as UNKNOWN.
     */
    private com.superagent.core.usage.TokenUsage checkCodeBuddy(String modelId, RunCredential credential)
            throws IOException, InterruptedException {
        if (credential == null) {
            throw new IOException("还没有配置 API Key");
        }
        Path bin = probe.findBinary("codebuddy").orElseThrow(() -> new IOException("codebuddy is not installed"));
        if (modelId == null || modelId.isBlank()) {
            throw new IOException("还没有可用来测试的模型（等模型列表刷新后再检查）");
        }
        String region = settings == null ? "internal"
                : settings.all().getOrDefault("codebuddy.internet-environment", "internal");
        if (region == null || region.isBlank()) {
            region = "internal";
        }
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("CODEBUDDY_API_KEY", credential.apiKey());
        if (credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
            env.put("CODEBUDDY_BASE_URL", credential.baseUrl().strip());
        }
        if (!"public".equalsIgnoreCase(region.strip())) {
            env.put("CODEBUDDY_INTERNET_ENVIRONMENT", region.strip().toLowerCase(java.util.Locale.ROOT));
        }
        String out = run(List.of(bin.toString(), "-p", "-y", "--output-format", "json", "--max-turns", "1",
                "--model", modelId, "--", PROMPT), null, env);
        JsonNode root = parseCliJsonObject(out, "codebuddy");
        if (root.path("is_error").asBoolean(false)) {
            throw new IOException(tail(root.path("result").asText("codebuddy reported an error")));
        }
        if (!"result".equals(root.path("type").asText(""))) {
            throw new IOException("codebuddy returned no usable result");
        }
        JsonNode u = root.path("usage");
        if (u.isObject()) {
            return new com.superagent.core.usage.TokenUsage(modelId, u.path("input_tokens").asLong(0),
                    u.path("cache_read_input_tokens").asLong(0), u.path("cache_creation_input_tokens").asLong(0),
                    0, u.path("output_tokens").asLong(0), 0, null);
        }
        return new com.superagent.core.usage.TokenUsage(modelId, 0, 0, 0, 0, 0, 0, null);
    }

    /**
     * Gemini CLI: one short {@code -p} turn under an isolated {@code GEMINI_CLI_HOME}. Version alone is not
     * auth; without a model the caller must leave health as UNKNOWN.
     */
    private com.superagent.core.usage.TokenUsage checkGemini(String modelId, RunCredential credential)
            throws IOException, InterruptedException {
        if (credential == null) {
            throw new IOException("还没有配置 API Key");
        }
        Path bin = probe.findBinary("gemini").orElseThrow(() -> new IOException("gemini is not installed"));
        if (modelId == null || modelId.isBlank()) {
            throw new IOException("还没有可用来测试的模型（等模型列表刷新后再检查）");
        }
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("GEMINI_API_KEY", credential.apiKey());
        if (credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
            env.put("GOOGLE_GEMINI_BASE_URL", credential.baseUrl().strip());
        }
        Path home = Files.createTempDirectory("sa-gemini-probe-");
        try {
            Files.createDirectories(home.resolve(".gemini"));
            Files.writeString(home.resolve(".gemini").resolve("settings.json"), "{\"mcpServers\":{}}");
            env.put("GEMINI_CLI_HOME", home.toString());
            env.put("HOME", home.toString());
            // stream-json: same stats fields as the runner parser (not the non-stream metrics blob).
            String out = run(List.of(bin.toString(), "-p", PROMPT, "--output-format", "stream-json",
                    "--approval-mode", "plan", "--skip-trust", "--model", modelId), null, env);
            JsonNode root = lastStreamEvent(out, "gemini");
            if ("error".equals(root.path("type").asText(""))
                    || (root.path("error").isObject() && !"result".equals(root.path("type").asText("")))) {
                throw new IOException(tail(root.path("error").path("message")
                        .asText(root.path("message").asText("gemini reported an error"))));
            }
            if ("result".equals(root.path("type").asText("")) && "error".equals(root.path("status").asText(""))) {
                throw new IOException(tail(root.path("error").path("message").asText("gemini reported an error")));
            }
            if (!"result".equals(root.path("type").asText("")) || !"success".equals(root.path("status").asText(""))) {
                throw new IOException("gemini returned no usable result");
            }
            JsonNode stats = root.path("stats");
            if (stats.isObject()) {
                long totalIn = stats.path("input_tokens").asLong(0);
                long cached = stats.path("cached").asLong(0);
                long output = stats.path("output_tokens").asLong(0);
                long explicit = stats.path("input").asLong(0);
                long in = explicit > 0 ? explicit : Math.max(0, totalIn - cached);
                return new com.superagent.core.usage.TokenUsage(modelId, in, cached, 0, 0, output, 0, null);
            }
            return new com.superagent.core.usage.TokenUsage(modelId, 0, 0, 0, 0, 0, 0, null);
        } finally {
            deleteTree(home);
        }
    }

    /**
     * MiniMax Code: one short {@code mcode exec} under an isolated {@code MINIMAX_DATA_DIR}. Version alone
     * is not auth; without a model the caller must leave health as UNKNOWN.
     */
    private com.superagent.core.usage.TokenUsage checkMinimax(String modelId, RunCredential credential)
            throws IOException, InterruptedException {
        if (credential == null) {
            throw new IOException("还没有配置 API Key");
        }
        Path bin = probe.findBinary("mcode").orElseThrow(() -> new IOException("mcode is not installed"));
        if (modelId == null || modelId.isBlank()) {
            throw new IOException("还没有可用来测试的模型（等模型列表刷新后再检查）");
        }
        Path data = Files.createTempDirectory("sa-mcode-probe-");
        try {
            String model = modelId.contains("/") ? modelId : "minimax/" + modelId;
            Files.writeString(data.resolve("config.yaml"),
                    "logLevel: info\ndefaultModel: \"" + model + "\"\nminimax_api:\n  apiKey: \""
                            + credential.apiKey().replace("\"", "\\\"") + "\"\nminimaxModelSource: minimax_api_key\n");
            Files.writeString(data.resolve("mcp.json"), "{\"mcpServers\":{}}");
            java.util.Map<String, String> env = new java.util.HashMap<>();
            env.put("MINIMAX_DATA_DIR", data.toString());
            env.put("MCODE_PROVIDER_API_KEY", credential.apiKey());
            if (credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
                env.put("MCODE_API_BASE_URL", credential.baseUrl().strip());
            }
            String out = run(List.of(bin.toString(), "exec", "--output-format", "stream-json",
                    "--permission", "off", "--max-steps", "1", "--model", model, PROMPT), null, env);
            long in = 0, cached = 0, outTokens = 0;
            boolean completed = false;
            for (String line : out.split("\\R")) {
                if (!line.strip().startsWith("{")) {
                    continue;
                }
                JsonNode e;
                try {
                    e = mapper.readTree(line);
                } catch (IOException parseErr) {
                    throw new IOException("mcode returned unreadable output", parseErr);
                }
                if ("exec.completed".equals(e.path("type").asText())) {
                    completed = true;
                    JsonNode u = e.path("result").path("usage");
                    in += u.path("input_tokens").asLong(u.path("inputTokens").asLong(0));
                    cached += u.path("cached_input_tokens").asLong(0);
                    outTokens += u.path("output_tokens").asLong(u.path("outputTokens").asLong(0));
                    if ("failed".equals(e.path("result").path("status").asText())) {
                        throw new IOException(tail(e.path("result").path("error").path("message")
                                .asText("mcode reported an error")));
                    }
                } else if ("turn.failed".equals(e.path("type").asText())) {
                    throw new IOException(tail(e.path("error").path("message").asText("mcode turn failed")));
                }
            }
            if (!completed) {
                throw new IOException("mcode returned no usable result");
            }
            return new com.superagent.core.usage.TokenUsage(modelId, Math.max(0, in - cached), cached, 0, 0, outTokens,
                    0, null);
        } finally {
            deleteTree(data);
        }
    }

    /** Parses a CLI dump into one JSON object; prefers the last object line / trailing object. */
    private JsonNode parseCliJsonObject(String out, String cli) throws IOException {
        if (out == null || out.isBlank()) {
            throw new IOException(cli + " returned empty output");
        }
        try {
            JsonNode root = mapper.readTree(out);
            if (root.isObject()) {
                return root;
            }
        } catch (IOException ignored) {
            // fall through to last-object scan
        }
        JsonNode last = null;
        for (String line : out.split("\\R")) {
            String t = line.strip();
            if (!t.startsWith("{")) {
                continue;
            }
            try {
                last = mapper.readTree(t);
            } catch (IOException ignored) {
                // keep scanning
            }
        }
        if (last == null || !last.isObject()) {
            try {
                last = mapper.readTree(lastJsonObject(out));
            } catch (IOException e) {
                throw new IOException(cli + " returned unreadable output", e);
            }
        }
        if (last == null || !last.isObject()) {
            throw new IOException(cli + " returned unreadable output");
        }
        return last;
    }

    /** Last JSONL event from a stream-json dump (must be an object). */
    private JsonNode lastStreamEvent(String out, String cli) throws IOException {
        if (out == null || out.isBlank()) {
            throw new IOException(cli + " returned empty output");
        }
        JsonNode last = null;
        for (String line : out.split("\\R")) {
            String t = line.strip();
            if (!t.startsWith("{")) {
                continue;
            }
            try {
                last = mapper.readTree(t);
            } catch (IOException ignored) {
                // keep scanning
            }
        }
        if (last == null || !last.isObject()) {
            throw new IOException(cli + " returned unreadable output");
        }
        return last;
    }

    /** Last JSON object in a multi-line CLI dump (Gemini {@code -o json} may print banners first). */
    private static String lastJsonObject(String out) {
        String last = "{}";
        for (String line : out.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("{") && t.endsWith("}")) {
                last = t;
            }
        }
        if ("{}".equals(last)) {
            int i = out.lastIndexOf('{');
            if (i >= 0) {
                last = out.substring(i).strip();
            }
        }
        return last;
    }

    /**
     * Qoder CN: {@code --list-models} is the whole check, and costs no quota. It proves the token (the CLI exits
     * non-zero when it isn't accepted), and the list is the account's own subscription, so a model on it is one the
     * account may run. A real turn per model would be a full agent call — the CLI's whole system prompt and tools —
     * every time, for nothing the list doesn't already say.
     */
    private com.superagent.core.usage.TokenUsage checkQoder(String modelId, RunCredential credential)
            throws IOException, InterruptedException {
        if (credential == null) {
            throw new IOException("还没有配置 API Key");
        }
        Path bin = probe.findBinary("qoderclicn").orElseThrow(() -> new IOException("qoderclicn is not installed"));
        String out;
        try {
            out = run(List.of(bin.toString(), "--list-models"), null, ProviderModelFetcher.qoderEnv(credential));
        } catch (IOException e) {
            throw ProviderModelFetcher.qoderFailure(e);
        }
        if (modelId != null && ProviderModelFetcher.qoderModels(out).stream().noneMatch(m -> m.id().equals(modelId))) {
            throw new IOException("这个令牌的账号不能用 " + modelId + "（不在 Qoder 返回的模型列表里）");
        }
        return null;
    }

    // --- claude ---
    private com.superagent.core.usage.TokenUsage checkClaude(String modelId, RunCredential credential) throws IOException, InterruptedException {
        java.util.Optional<ApiAccess> access = ApiAccess.anthropic(credential);
        if (access.isEmpty()) {
            return checkClaudeCli(modelId);
        }
        return checkAnthropic(access.get(), modelId);
    }

    /**
     * The vendors reached by key alone: no key, no check. {@code modelId} null (a new key, no model list yet) asks
     * for the list instead of an inference, which still proves the key.
     */
    private com.superagent.core.usage.TokenUsage checkVendor(String providerKey, String modelId, RunCredential credential) throws IOException, InterruptedException {
        var entry = com.superagent.core.provider.ProviderCatalog.find(providerKey).orElseThrow();
        ApiAccess access = ApiAccess.of(entry, credential).orElseThrow(() -> new IOException("还没有配置 API Key"));
        if (modelId == null) {
            send(access.base(), access.authorizeBearer(HttpRequest.newBuilder(URI.create(access.modelsUrl(providerKey)))
                    .timeout(Duration.ofSeconds(30)).GET()));
            return null;
        } else if (com.superagent.core.provider.ProviderCatalog.ANTHROPIC.equals(entry.access().protocol())) {
            return checkAnthropic(access, modelId);
        } else {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelId);
            body.put("max_tokens", 8);
            body.putArray("messages").addObject().put("role", "user").put("content", PROMPT);
            HttpResponse<String> resp = sendInference(access.base(), access.authorizeBearer(HttpRequest.newBuilder(URI.create(access.base() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60)).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))));
            JsonNode u = mapper.readTree(resp.body()).path("usage");
            long cached = u.path("prompt_tokens_details").path("cached_tokens").asLong(0);
            return new com.superagent.core.usage.TokenUsage(modelId, Math.max(0, u.path("prompt_tokens").asLong(0) - cached),
                    cached, 0, 0, u.path("completion_tokens").asLong(0),
                    u.path("completion_tokens_details").path("reasoning_tokens").asLong(0), null);
        }
    }

    private com.superagent.core.usage.TokenUsage checkAnthropic(ApiAccess access, String modelId) throws IOException, InterruptedException {
        String base = access.base();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelId);
        body.put("max_tokens", 8);
        body.putArray("messages").addObject().put("role", "user").put("content", PROMPT);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                .timeout(Duration.ofSeconds(60))
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        access.authorize(req);
        HttpResponse<String> resp = sendInference(base, req);
        JsonNode root = mapper.readTree(resp.body());
        if (!root.path("content").isArray()) {
            throw new IOException("response has no content");
        }
        JsonNode u = root.path("usage");
        return new com.superagent.core.usage.TokenUsage(modelId, u.path("input_tokens").asLong(0),
                u.path("cache_read_input_tokens").asLong(0), u.path("cache_creation_input_tokens").asLong(0), 0,
                u.path("output_tokens").asLong(0), 0, null);
    }

    private HttpResponse<String> send(String base, HttpRequest.Builder req) throws IOException, InterruptedException {
        HttpResponse<String> resp = sendRaw(base, req);
        if (resp.statusCode() != 200) {
            throw new IOException(cause(resp.statusCode(), resp.body()) + "（HTTP " + resp.statusCode() + errorDetail(resp.body()) + "）");
        }
        return resp;
    }

    /**
     * The endpoint says the model itself is gone (retired, renamed, or never existed there) — not the key.
     * The caller may try another model instead of failing the key over one dead name.
     */
    public static class ModelGoneException extends IOException {
        public ModelGoneException(String message) {
            super(message);
        }
    }

    /** Whether a failed inference says the model is gone rather than the key being wrong. */
    static boolean modelGone(int status, String body) {
        String raw = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        String m = (errorMessage(body) + " " + raw).toLowerCase(java.util.Locale.ROOT);
        return m.contains("model not exist") || m.contains("no longer available") || m.contains("model_not_found")
                || m.contains("model not found") || m.contains("invalid model") || m.contains("unknown model")
                || (status == 404 && m.contains("model"));
    }

    /** One inference: a model that is gone says so, so the caller can try another one. */
    private HttpResponse<String> sendInference(String base, HttpRequest.Builder req) throws IOException, InterruptedException {
        HttpResponse<String> resp = sendRaw(base, req);
        if (resp.statusCode() != 200) {
            String reason = cause(resp.statusCode(), resp.body()) + "（HTTP " + resp.statusCode() + errorDetail(resp.body()) + "）";
            if (modelGone(resp.statusCode(), resp.body())) {
                throw new ModelGoneException(reason);
            }
            throw new IOException(reason);
        }
        return resp;
    }

    /** The response whatever its status; only an unreachable endpoint throws. */
    private HttpResponse<String> sendRaw(String base, HttpRequest.Builder req) throws IOException, InterruptedException {
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException | java.net.UnknownHostException | java.net.http.HttpTimeoutException e) {
            // HttpClient's ConnectException usually has no message: name the cause so the UI can say something.
            throw new ProbeUnreachableException("无法连接 " + base + "（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）");
        } catch (IOException e) {
            throw new ProbeUnreachableException("请求 " + base + " 失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        return resp;
    }

    private com.superagent.core.usage.TokenUsage checkClaudeCli(String modelId) throws IOException, InterruptedException {
        Path claude = probe.findBinary("claude").orElseThrow(() -> new IOException("claude is not installed"));
        String out = run(List.of(claude.toString(), "-p", "--output-format", "json", "--max-turns", "1",
                "--model", modelId, "--", PROMPT), null);
        JsonNode root = mapper.readTree(out);
        if (root.path("is_error").asBoolean(false)) {
            throw new IOException(tail(root.path("result").asText("claude reported an error")));
        }
        // The whole run, the CLI's own side calls included (modelUsage); usage alone is the main call.
        long[] t = new long[5];
        root.path("modelUsage").forEach(m -> {
            t[0] += m.path("inputTokens").asLong(0);
            t[1] += m.path("cacheReadInputTokens").asLong(0);
            t[2] += m.path("cacheCreationInputTokens").asLong(0);
            t[3] += m.path("outputTokens").asLong(0);
        });
        return new com.superagent.core.usage.TokenUsage(modelId, t[0], t[1], t[2], 0, t[3], 0, null);
    }

    // --- codex ---

    /**
     * With a key: one tiny Responses API call on the credential's own endpoint (never the machine's Codex config).
     * Without one (a ChatGPT login): a Codex run, the only way in.
     */
    private com.superagent.core.usage.TokenUsage checkCodex(String modelId, RunCredential credential) throws IOException, InterruptedException {
        if (credential != null) {
            var entry = com.superagent.core.provider.ProviderCatalog.find("codex").orElseThrow();
            ApiAccess access = ApiAccess.of(entry, credential).orElseThrow();
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelId);
            body.put("input", PROMPT);
            body.put("max_output_tokens", 16);
            HttpResponse<String> resp = sendInference(access.base(), access.authorizeBearer(HttpRequest.newBuilder(URI.create(access.base() + "/responses"))
                    .timeout(Duration.ofSeconds(60)).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))));
            JsonNode u = mapper.readTree(resp.body()).path("usage");
            long cached = u.path("input_tokens_details").path("cached_tokens").asLong(0);
            return new com.superagent.core.usage.TokenUsage(modelId, Math.max(0, u.path("input_tokens").asLong(0) - cached), cached,
                    0, 0, u.path("output_tokens").asLong(0), u.path("output_tokens_details").path("reasoning_tokens").asLong(0), null);
        }
        Path codex = probe.findBinary("codex").orElseThrow(() -> new IOException("codex is not installed"));
        // Read-only sandbox, prompt on stdin: the probe can't touch anything even if the model tried.
        List<String> argv = new java.util.ArrayList<>(List.of(codex.toString(), "exec", "--json", "--skip-git-repo-check",
                "-s", "read-only", "-m", modelId, "-"));
        String out = run(argv, PROMPT, java.util.Map.of(), true);
        long in = 0, cached = 0, outTokens = 0, reasoning = 0;
        for (String line : out.split("\\R")) {
            if (line.contains("turn.completed")) {
                JsonNode u = mapper.readTree(line).path("usage");
                in += u.path("input_tokens").asLong(0);
                cached += u.path("cached_input_tokens").asLong(0);
                outTokens += u.path("output_tokens").asLong(0);
                reasoning += u.path("reasoning_output_tokens").asLong(0);
            }
        }
        return new com.superagent.core.usage.TokenUsage(modelId, Math.max(0, in - cached), cached, 0, 0, outTokens, reasoning, null);
    }

    // --- helpers ---

    private String run(List<String> argv, String stdin) throws IOException, InterruptedException {
        return run(argv, stdin, java.util.Map.of());
    }

    /** Runs argv (no shell) in a scratch dir; non-zero exit or timeout is a failed check. */
    private String run(List<String> argv, String stdin, java.util.Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        return run(argv, stdin, extraEnv, false);
    }

    private String run(List<String> argv, String stdin, java.util.Map<String, String> extraEnv, boolean codexLogin)
            throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("sa-infer-");
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
            pb.environment().put("PATH", String.join(File.pathSeparator, probe.searchPath()));
            pb.environment().putAll(extraEnv);
            if (codexLogin) {
                pb.environment().remove("CODEX_API_KEY");
                pb.environment().remove("OPENAI_API_KEY");
            }
            Process p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
            if (!p.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("timed out after " + CLI_TIMEOUT_SECONDS + "s");
            }
            String out = output.join();
            if (p.exitValue() != 0) {
                String detail = cliFailure(p.exitValue(), out);
                throw unreachable(out) ? new ProbeUnreachableException(detail) : new IOException(detail);
            }
            return out;
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * Why a CLI run failed, in a line an administrator can act on: the exit code, then Codex's own failure
     * ({@code turn.failed}, else its last error event) or, for plain output, its last lines. Config warnings
     * ("ignoring unrecognized configuration settings") are left out: they're noise that used to push the real
     * reason out of the shown tail.
     */
    static String cliFailure(int exit, String out) {
        String failed = null;
        String lastError = null;
        List<String> plain = new java.util.ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        for (String line : (out == null ? "" : out).split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("{")) {
                try {
                    JsonNode e = json.readTree(t);
                    String type = e.path("type").asText("");
                    if (type.equals("turn.failed")) {
                        failed = e.path("error").path("message").asText(null);
                    } else if (type.equals("error")) {
                        lastError = e.path("message").asText(null);
                    } else if (type.equals("item.completed") && "error".equals(e.path("item").path("type").asText())) {
                        String message = e.path("item").path("message").asText("");
                        if (!message.contains("unrecognized configuration settings")) {
                            lastError = message;
                        }
                    }
                    continue;
                } catch (IOException notJson) {
                    // a plain line that happens to start with a brace
                }
            }
            plain.add(t);
        }
        String reason = failed != null ? failed : lastError;
        if (reason == null) {
            reason = plain.isEmpty() ? "没有输出任何错误信息（CLI 开始请求后就退出了）"
                    : String.join("\n", plain.subList(Math.max(0, plain.size() - 8), plain.size()));
        }
        String code = exit == 137 || exit == 143 ? exit + "（进程被终止）" : String.valueOf(exit);
        return "退出码 " + code + "：" + reason.strip();
    }

    /** CLI output that means the CLI couldn't reach its service, rather than that the model refused. */
    private static final List<String> UNREACHABLE = List.of("Failed to load cloud config bundle", "error sending request",
            "Connection refused", "connection error", "ENOTFOUND", "ECONNREFUSED", "ETIMEDOUT", "stream disconnected");

    static boolean unreachable(String out) {
        return out != null && UNREACHABLE.stream().anyMatch(out::contains);
    }

    /** Best-effort cleanup of the scratch dir; a leftover temp dir isn't worth failing the check over. */
    private static void deleteTree(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // the OS temp cleaner gets it
        }
    }

    /** What an HTTP failure most likely means, in words an administrator can act on. */
    static String cause(int status) {
        return switch (status) {
            case 401 -> "API Key 无效或已失效";
            case 403 -> "API Key 没有权限，或账号被限制";
            case 402 -> "账户余额不足";
            case 404 -> "地址或模型不存在，请检查 Endpoint";
            case 429 -> "额度用完或请求过于频繁";
            default -> status >= 500 ? "服务端出错，稍后再试" : "请求被拒绝";
        };
    }

    /**
     * A 403 on an inference can mean the key authenticated but the account may not run the one model probed
     * (DashScope's "Access to model denied") — a different problem with a different fix than a restricted key.
     */
    static String cause(int status, String body) {
        if (status == 403 && modelDenied(body)) {
            return "Key 已通过验证，但账号无权使用测试的模型（请在服务商控制台开通该模型，或换模型检查）";
        }
        return cause(status);
    }

    private static boolean modelDenied(String body) {
        String m = errorMessage(body).toLowerCase(java.util.Locale.ROOT);
        return m.contains("model")
                && (m.contains("denied") || m.contains("eligible") || m.contains("access") || m.contains("permission") || m.contains("not allowed"));
    }

    /** The API's own error message: Anthropic/OpenAI wrap it in {@code error}, DashScope-style bodies carry it at the top level. */
    static String errorDetail(String body) {
        if (blank(body)) {
            return "";
        }
        String msg = errorMessage(body);
        return ": " + (msg.isBlank() ? tail(body) : tail(msg));
    }

    private static String errorMessage(String body) {
        if (blank(body)) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(body);
            String msg = root.path("error").path("message").asText("");
            return msg.isBlank() ? root.path("message").asText("") : msg;
        } catch (IOException e) {
            return "";
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() <= 300 ? t : "…" + t.substring(t.length() - 300);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
