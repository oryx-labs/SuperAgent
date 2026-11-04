package com.superagent.web.provider;

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
        switch (providerKey) {
            case "claude" -> checkClaude(modelId);
            case "codex" -> checkCodex(modelId);
            default -> throw new IOException("Inference check is not available for " + providerKey + " yet");
        }
    }

    // --- claude ---

    private void checkClaude(String modelId) throws IOException, InterruptedException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String authToken = System.getenv("ANTHROPIC_AUTH_TOKEN");
        if (blank(apiKey) && blank(authToken)) {
            checkClaudeCli(modelId);
            return;
        }
        String base = System.getenv("ANTHROPIC_BASE_URL");
        base = blank(base) ? "https://api.anthropic.com" : base.replaceAll("/+$", "");

        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelId);
        body.put("max_tokens", 8);
        body.putArray("messages").addObject().put("role", "user").put("content", PROMPT);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                .timeout(Duration.ofSeconds(60))
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (!blank(apiKey)) {
            req.header("x-api-key", apiKey);
        } else {
            req.header("Authorization", "Bearer " + authToken);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + errorDetail(resp.body()));
        }
        JsonNode root = mapper.readTree(resp.body());
        if (!root.path("content").isArray()) {
            throw new IOException("response has no content");
        }
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

    private void checkCodex(String modelId) throws IOException, InterruptedException {
        Path codex = probe.findBinary("codex").orElseThrow(() -> new IOException("codex is not installed"));
        // Read-only sandbox, prompt on stdin: the probe can't touch anything even if the model tried.
        run(List.of(codex.toString(), "exec", "--json", "--skip-git-repo-check", "-s", "read-only",
                "-m", modelId, "-"), PROMPT);
    }

    // --- helpers ---

    /** Runs argv (no shell) in a scratch dir; non-zero exit or timeout is a failed check. */
    private String run(List<String> argv, String stdin) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("sa-infer-");
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
            pb.environment().put("PATH", String.join(File.pathSeparator, probe.searchPath()));
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
                throw new IOException("exited with " + p.exitValue() + ": " + tail(out));
            }
            return out;
        } finally {
            deleteTree(dir);
        }
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
