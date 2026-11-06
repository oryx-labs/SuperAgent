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

/**
 * Checks whether a listed model can actually complete an inference — a model list only says
 * a model exists, not that this account/gateway can run it. Each check is the cheapest real
 * call that exercises the same credentials the runtime uses:
 * <ul>
 *   <li><b>claude</b> with an API key / auth token: one {@code POST /v1/messages} with a tiny
 *       {@code max_tokens}, against the same base URL Claude Code uses.</li>
 *   <li><b>claude</b> on a subscription login, and <b>codex</b>: a one-shot CLI run, since there
 *       is no key to call the API with. These cost a full CLI turn, so they're kept to the few
 *       models those paths list.</li>
 * </ul>
 */
@Component
public class ProviderInferenceProbe {

    // Any reply counts as success — the content is never inspected.
    private static final String PROMPT = "你好";
    private static final int CLI_TIMEOUT_SECONDS = 90;

    private final ProviderProbe probe;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ProviderInferenceProbe(ProviderProbe probe) {
        this.probe = probe;
    }

    /** Runs one inference against {@code modelId}; returns normally on success, throws with a readable reason. */
    public void check(String providerKey, String modelId) throws IOException, InterruptedException {
        check(providerKey, modelId, null);
    }

    /** The same check through a specific pool credential (null = the server's environment / CLI login). */
    public void check(String providerKey, String modelId, RunCredential credential) throws IOException, InterruptedException {
        switch (providerKey) {
            case "claude" -> checkClaude(modelId, credential);
            case "codex" -> checkCodex(modelId, credential);
            case "deepseek", "glm", "kimi", "qwen" -> checkVendor(providerKey, modelId, credential);
            default -> throw new IOException("Inference check is not available for " + providerKey + " yet");
        }
    }

    // --- claude ---

    private void checkClaude(String modelId, RunCredential credential) throws IOException, InterruptedException {
        java.util.Optional<ApiAccess> access = ApiAccess.anthropic(credential);
        if (access.isEmpty()) {
            checkClaudeCli(modelId);
            return;
        }
        checkAnthropic(access.get(), modelId);
    }

    /**
     * The vendors reached by key alone: no key, no check. {@code modelId} null (a new key, no model list yet) asks
     * for the list instead of an inference, which still proves the key.
     */
    private void checkVendor(String providerKey, String modelId, RunCredential credential) throws IOException, InterruptedException {
        var entry = com.superagent.core.provider.ProviderCatalog.find(providerKey).orElseThrow();
        ApiAccess access = ApiAccess.of(entry, credential).orElseThrow(() -> new IOException("还没有配置 API Key"));
        if (modelId == null) {
            send(access.base(), access.authorizeBearer(HttpRequest.newBuilder(URI.create(access.modelsUrl(providerKey)))
                    .timeout(Duration.ofSeconds(30)).GET()));
        } else if (com.superagent.core.provider.ProviderCatalog.ANTHROPIC.equals(entry.access().protocol())) {
            checkAnthropic(access, modelId);
        } else {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelId);
            body.put("max_tokens", 8);
            body.putArray("messages").addObject().put("role", "user").put("content", PROMPT);
            send(access.base(), access.authorizeBearer(HttpRequest.newBuilder(URI.create(access.base() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60)).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))));
        }
    }

    private void checkAnthropic(ApiAccess access, String modelId) throws IOException, InterruptedException {
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
        HttpResponse<String> resp = send(base, req);
        JsonNode root = mapper.readTree(resp.body());
        if (!root.path("content").isArray()) {
            throw new IOException("response has no content");
        }
    }

    private HttpResponse<String> send(String base, HttpRequest.Builder req) throws IOException, InterruptedException {
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
        if (resp.statusCode() != 200) {
            throw new IOException(cause(resp.statusCode()) + "（HTTP " + resp.statusCode() + errorDetail(resp.body()) + "）");
        }
        return resp;
    }

    private void checkClaudeCli(String modelId) throws IOException, InterruptedException {
        Path claude = probe.findBinary("claude").orElseThrow(() -> new IOException("claude is not installed"));
        String out = run(List.of(claude.toString(), "-p", "--output-format", "json", "--max-turns", "1",
                "--model", modelId, "--", PROMPT), null);
        JsonNode root = mapper.readTree(out);
        if (root.path("is_error").asBoolean(false)) {
            throw new IOException(tail(root.path("result").asText("claude reported an error")));
        }
    }

    // --- codex ---

    private void checkCodex(String modelId, RunCredential credential) throws IOException, InterruptedException {
        Path codex = probe.findBinary("codex").orElseThrow(() -> new IOException("codex is not installed"));
        // Read-only sandbox, prompt on stdin: the probe can't touch anything even if the model tried.
        List<String> argv = new java.util.ArrayList<>(List.of(codex.toString(), "exec", "--json", "--skip-git-repo-check",
                "-s", "read-only", "-m", modelId));
        java.util.Map<String, String> env = new java.util.HashMap<>();
        if (credential != null) {
            env.put("CODEX_API_KEY", credential.apiKey()); // env, never argv
            // Like a run: the machine's config.toml may pick a relay provider, which would check its key, not this one.
            argv.addAll(List.of("-c", "model_provider=\"openai\""));
            if (credential.baseUrl() != null) {
                argv.addAll(List.of("-c", "openai_base_url=" + mapper.writeValueAsString(credential.baseUrl())));
            }
        }
        argv.add("-");
        run(argv, PROMPT, env);
    }

    // --- helpers ---

    private String run(List<String> argv, String stdin) throws IOException, InterruptedException {
        return run(argv, stdin, java.util.Map.of());
    }

    /** Runs argv (no shell) in a scratch dir; non-zero exit or timeout is a failed check. */
    private String run(List<String> argv, String stdin, java.util.Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("sa-infer-");
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
            pb.environment().put("PATH", String.join(File.pathSeparator, probe.searchPath()));
            pb.environment().putAll(extraEnv);
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

    /** The API's own error message when the body is an Anthropic-style error, else a short raw tail. */
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

    private String errorDetail(String body) {
        if (blank(body)) {
            return "";
        }
        try {
            String msg = mapper.readTree(body).path("error").path("message").asText("");
            return ": " + (msg.isBlank() ? tail(body) : tail(msg));
        } catch (IOException e) {
            return ": " + tail(body);
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
