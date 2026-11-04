package com.superagent.web.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.api.provider.ProviderModelDto;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the models a CLI can actually run, from the CLI itself or the credentials it
 * uses — never from a hard-coded list:
 * <ul>
 *   <li><b>codex</b>: {@code codex debug models}, the catalogue behind Codex's own model picker
 *       (includes per-model reasoning-effort levels and defaults).</li>
 *   <li><b>claude</b>: {@code GET /v1/models} with the same API key / base URL Claude Code
 *       uses (from this process's environment). A gateway may serve other vendors' models
 *       and omit capability data; those cases are flagged rather than guessed.</li>
 * </ul>
 */
@Component
public class ProviderModelFetcher {

    /** Ids that are clearly not chat models (embeddings, speech, images, ...) or gateway test entries. */
    private static final Pattern NON_CHAT = Pattern.compile(
            "embed|tts|transcribe|realtime|image|rerank|intent-detect|whisper|moderation|dall-e|audio|speech|^mock-|^test-");
    private static final Pattern CLI_EFFORT_HELP = Pattern.compile("--effort\\s+<level>.*?\\(([a-z, ]+)\\)", Pattern.DOTALL);
    private static final List<String> EFFORT_ORDER = List.of("minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    private static final int MAX_PAGES = 20;

    /** A fetched model list plus a human-readable description of where it came from. */
    public record Result(List<ProviderModelDto> models, String source) {
    }

    private final ProviderProbe probe;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ProviderModelFetcher(ProviderProbe probe) {
        this.probe = probe;
    }

    /** Fetches the model list for a catalogue key; throws with a readable message on failure. */
    public Result fetch(String providerKey) throws IOException, InterruptedException {
        return switch (providerKey) {
            case "codex" -> fetchCodex();
            case "claude" -> fetchClaude();
            default -> throw new IOException("Model listing is not available for " + providerKey + " yet");
        };
    }

    // --- codex ---

    private Result fetchCodex() throws IOException, InterruptedException {
        Path codex = probe.findBinary("codex").orElseThrow(() -> new IOException("codex is not installed"));
        JsonNode root = mapper.readTree(run(List.of(codex.toString(), "debug", "models"), 60));
        List<ProviderModelDto> out = new ArrayList<>();
        for (JsonNode m : root.path("models")) {
            if ("hide".equals(m.path("visibility").asText())) {
                continue; // internal models Codex keeps out of its own picker
            }
            List<String> efforts = new ArrayList<>();
            for (JsonNode level : m.path("supported_reasoning_levels")) {
                String e = level.isObject() ? level.path("effort").asText("") : level.asText("");
                if (!e.isBlank()) {
                    efforts.add(e);
                }
            }
            out.add(new ProviderModelDto(
                    m.path("slug").asText(),
                    textOrNull(m, "display_name"),
                    efforts,
                    textOrNull(m, "default_reasoning_level"),
                    efforts.isEmpty() ? "none" : "model",
                    m.hasNonNull("context_window") ? m.get("context_window").asInt() : null,
                    true));
        }
        return new Result(out, "codex debug models");
    }

    // --- claude ---

    private Result fetchClaude() throws IOException, InterruptedException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String authToken = System.getenv("ANTHROPIC_AUTH_TOKEN");
        String base = System.getenv("ANTHROPIC_BASE_URL");
        boolean viaGateway = base != null && !base.isBlank();
        base = viaGateway ? base.replaceAll("/+$", "") : "https://api.anthropic.com";
        List<String> cliEfforts = claudeCliEfforts();

        if (blank(apiKey) && blank(authToken)) {
            // Subscription (OAuth) login: no key to list the account's models with. The CLI's
            // documented aliases still work; say so rather than inventing a list.
            List<ProviderModelDto> aliases = new ArrayList<>();
            for (String alias : List.of("fable", "opus", "sonnet")) {
                aliases.add(new ProviderModelDto(alias, alias + " (alias)", cliEfforts, null,
                        cliEfforts.isEmpty() ? "none" : "cli", null, true));
            }
            return new Result(aliases, "claude --help aliases (no API key in the server environment)");
        }

        List<ProviderModelDto> out = new ArrayList<>();
        String afterId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = base + "/v1/models?limit=1000"
                    + (afterId != null ? "&after_id=" + URLEncoder.encode(afterId, StandardCharsets.UTF_8) : "");
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("anthropic-version", "2023-06-01")
                    .GET();
            if (!blank(apiKey)) {
                req.header("x-api-key", apiKey);
            } else {
                req.header("Authorization", "Bearer " + authToken);
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("GET /v1/models returned HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode m : root.path("data")) {
                String id = m.path("id").asText("");
                if (id.isBlank() || NON_CHAT.matcher(id).find()) {
                    continue;
                }
                boolean nativeModel = id.startsWith("claude-");
                List<String> modelEfforts = effortsFromCapabilities(m.path("capabilities").path("effort"));
                List<String> efforts;
                String effortSource;
                if (!modelEfforts.isEmpty()) {
                    efforts = modelEfforts;
                    effortSource = "model";
                } else if (nativeModel && !cliEfforts.isEmpty()) {
                    efforts = cliEfforts;      // levels Claude Code accepts; this model's support isn't reported
                    effortSource = "cli";
                } else {
                    efforts = List.of();
                    effortSource = "none";
                }
                out.add(new ProviderModelDto(id, textOrNull(m, "display_name"), efforts, null, effortSource,
                        m.hasNonNull("max_input_tokens") ? m.get("max_input_tokens").asInt() : null, nativeModel));
            }
            if (!root.path("has_more").asBoolean(false) || !root.hasNonNull("last_id")) {
                break;
            }
            afterId = root.get("last_id").asText();
        }
        out.sort(Comparator.comparing((ProviderModelDto m) -> !m.nativeModel()).thenComparing(ProviderModelDto::id));
        return new Result(out, viaGateway ? "GET /v1/models (ANTHROPIC_BASE_URL gateway)" : "GET /v1/models");
    }

    private static List<String> effortsFromCapabilities(JsonNode effort) {
        if (!effort.isObject() || !effort.path("supported").asBoolean(false)) {
            return List.of();
        }
        Set<String> levels = new LinkedHashSet<>();
        effort.fieldNames().forEachRemaining(name -> {
            if (!"supported".equals(name) && effort.path(name).path("supported").asBoolean(false)) {
                levels.add(name);
            }
        });
        List<String> ordered = new ArrayList<>(levels);
        ordered.sort(Comparator.comparingInt(l -> EFFORT_ORDER.indexOf(l) < 0 ? 99 : EFFORT_ORDER.indexOf(l)));
        return ordered;
    }

    /** Effort levels the installed Claude Code accepts, parsed from its own --help (empty if unavailable). */
    private List<String> claudeCliEfforts() {
        try {
            Path claude = probe.findBinary("claude").orElse(null);
            if (claude == null) {
                return List.of();
            }
            Matcher m = CLI_EFFORT_HELP.matcher(run(List.of(claude.toString(), "--help"), 15));
            if (!m.find()) {
                return List.of();
            }
            return Arrays.stream(m.group(1).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // --- helpers ---

    /**
     * Runs argv (no shell) and returns stdout. stdout is drained concurrently with the wait,
     * because a large output (codex's catalogue is ~600KB) would otherwise fill the pipe and hang.
     */
    private String run(List<String> argv, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("PATH", String.join(File.pathSeparator, probe.searchPath()));
        Process p = pb.start();
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(argv.get(0) + " timed out after " + timeoutSeconds + "s");
        }
        if (p.exitValue() != 0) {
            throw new IOException(String.join(" ", argv.subList(1, argv.size())) + " exited with " + p.exitValue());
        }
        return new String(stdout.join(), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
