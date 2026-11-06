package com.superagent.platform.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The CLI credentials this server's machine already has — what an admin set up by hand before the console existed:
 * the server's environment variables and the CLIs' own config files. Read so they can be shown and managed in the
 * console like any other key. Subscription logins (claude login / ChatGPT login) carry no API key and aren't listed.
 */
@Component
public class MachineCredentials {

    /** One credential found on the machine; {@code where} says which variable or file it came from. */
    public record Found(String providerKey, String baseUrl, String apiKey, boolean bearer, String where) {
        @Override
        public String toString() {
            return "Found[" + providerKey + " from " + where + "]";
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Function<String, String> env;
    private final Path claudeDir;
    private final Path codexHome;

    @org.springframework.beans.factory.annotation.Autowired
    public MachineCredentials(@Value("${superagent.provider.claude-config-dir:}") String claudeDir,
                              @Value("${superagent.provider.codex-home:}") String codexHome) {
        this(System::getenv, dir(claudeDir, "CLAUDE_CONFIG_DIR", ".claude"), dir(codexHome, "CODEX_HOME", ".codex"));
    }

    MachineCredentials(Function<String, String> env, Path claudeDir, Path codexHome) {
        this.env = env;
        this.claudeDir = claudeDir;
        this.codexHome = codexHome;
    }

    /** The CLI's config dir: the setting, else the CLI's own environment variable, else its default under home. */
    static Path dir(String configured, String envVar, String defaultName) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        String fromEnv = System.getenv(envVar);
        return fromEnv != null && !fromEnv.isBlank() ? Path.of(fromEnv) : Path.of(System.getProperty("user.home"), defaultName);
    }

    /** A CLI's folder under the same home as Claude's config (the writer's tests point this at a temp dir). */
    public Path home(String name) {
        return claudeDir.resolveSibling(name);
    }

    public Path claudeDir() {
        return claudeDir;
    }

    public Path codexHome() {
        return codexHome;
    }

    public List<Found> find(String providerKey) {
        return switch (providerKey) {
            case "claude" -> claude();
            case "codex" -> codex();
            default -> vendor(providerKey);
        };
    }

    /** The others: the catalogue's variables in the server's environment. */
    private List<Found> vendor(String providerKey) {
        var access = com.superagent.core.provider.ProviderCatalog.find(providerKey).map(e -> e.access()).orElse(null);
        String key = access == null || access.keyEnv() == null ? null : blankToNull(env.apply(access.keyEnv()));
        if (key == null) {
            return List.of();
        }
        String base = access.baseUrlEnv() == null ? null : blankToNull(env.apply(access.baseUrlEnv()));
        return List.of(new Found(providerKey, base, key, "glm".equals(providerKey), "服务器环境变量 " + access.keyEnv()));
    }

    private List<Found> claude() {
        List<Found> out = new ArrayList<>();
        addClaude(out, env, "服务器环境变量");
        Map<String, String> settingsEnv = new LinkedHashMap<>();
        JsonNode settings = readJson(claudeDir.resolve("settings.json"));
        if (settings != null) {
            settings.path("env").fields().forEachRemaining(e -> settingsEnv.put(e.getKey(), e.getValue().asText("")));
        }
        addClaude(out, settingsEnv::get, "~/.claude/settings.json");
        return out;
    }

    /** Claude Code prefers an API key over an auth token when both are set; follow the same order. */
    private static void addClaude(List<Found> out, Function<String, String> vars, String where) {
        String base = blankToNull(vars.apply("ANTHROPIC_BASE_URL"));
        String apiKey = blankToNull(vars.apply("ANTHROPIC_API_KEY"));
        String token = blankToNull(vars.apply("ANTHROPIC_AUTH_TOKEN"));
        if (apiKey != null) {
            out.add(new Found("claude", base, apiKey, false, where));
        } else if (token != null) {
            out.add(new Found("claude", base, token, true, where));
        }
    }

    private List<Found> codex() {
        List<Found> out = new ArrayList<>();
        String base = codexEndpoint(codexHome.resolve("config.toml"));
        for (String var : List.of("CODEX_API_KEY", "OPENAI_API_KEY")) {
            String key = blankToNull(env.apply(var));
            if (key != null) {
                out.add(new Found("codex", base, key, false, "服务器环境变量 " + var));
                break;
            }
        }
        JsonNode auth = readJson(codexHome.resolve("auth.json"));
        String fileKey = auth == null ? null : blankToNull(auth.path("OPENAI_API_KEY").asText(null));
        if (fileKey != null) {
            out.add(new Found("codex", base, fileKey, false, "~/.codex/auth.json"));
        }
        return out;
    }

    /**
     * Where this machine's Codex sends its key: the {@code base_url} of the provider {@code model_provider} selects
     * (a relay set up as {@code [model_providers.x]}), else the top-level {@code openai_base_url}.
     */
    static String codexEndpoint(Path configToml) {
        if (!Files.isRegularFile(configToml)) {
            return null;
        }
        try {
            String provider = null;
            boolean inProvider = false, topLevel = true;
            for (String line : Files.readAllLines(configToml, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("[")) {
                    topLevel = false;
                    inProvider = provider != null && (t.equals("[model_providers." + provider + "]")
                            || t.equals("[model_providers.\"" + provider + "\"]"));
                } else if (topLevel && t.startsWith("model_provider") && t.substring(14).strip().startsWith("=")) {
                    provider = tomlString(t);
                } else if (inProvider && t.startsWith("base_url") && t.substring(8).strip().startsWith("=")) {
                    return tomlString(t);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return openaiBaseUrl(configToml);
    }

    private static String tomlString(String keyValueLine) {
        int eq = keyValueLine.indexOf('=');
        String v = eq < 0 ? "" : keyValueLine.substring(eq + 1).strip();
        int hash = v.indexOf(" #");
        return blankToNull((hash >= 0 ? v.substring(0, hash) : v).strip().replaceAll("^[\"']|[\"']$", ""));
    }

    /** The top-level {@code openai_base_url} of a Codex config.toml (before any [table]), or null. */
    static String openaiBaseUrl(Path configToml) {
        if (!Files.isRegularFile(configToml)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(configToml, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("[")) {
                    return null;
                }
                if (t.startsWith("openai_base_url")) {
                    return tomlString(t);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private JsonNode readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            return null; // unreadable or not JSON: nothing to import from it
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
