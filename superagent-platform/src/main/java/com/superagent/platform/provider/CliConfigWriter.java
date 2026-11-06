package com.superagent.platform.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.RunCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps each CLI's own configuration on the key runs use, so the machine needs no hand setup: whoever runs the
 * CLI on this server — SuperAgent's runs, or an admin at a shell — gets the console's current entry. Rewritten on
 * a background thread whenever the choice changes (startup, a key added / edited / reordered, a failover, a
 * recovered key taking over again).
 *
 * <ul>
 *   <li>Claude Code: the {@code env} block of {@code settings.json} ({@code ANTHROPIC_BASE_URL} plus
 *       {@code ANTHROPIC_API_KEY} or {@code ANTHROPIC_AUTH_TOKEN}); the file's other settings are left alone.</li>
 *   <li>Codex: {@code auth.json} in API-key mode (the same file {@code codex login --with-api-key} writes) and the
 *       top-level {@code openai_base_url} of {@code config.toml}.</li>
 * </ul>
 *
 * Before the first change to a file its original is kept beside it ({@code .superagent-backup}); when no console
 * entry is usable any more, the original settings are put back. Files are written owner-only (they hold a key).
 */
@Component
public class CliConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(CliConfigWriter.class);
    private static final List<String> CLAUDE_VARS = List.of("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN");
    private static final String BACKUP = ".superagent-backup";
    private static final String ABSENT = ".superagent-absent";

    private final CredentialService credentials;
    private final MachineCredentials machine;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cli-config");
        t.setDaemon(true);
        return t;
    });

    public CliConfigWriter(CredentialService credentials, MachineCredentials machine,
                           @Value("${superagent.provider.cli-config.enabled:true}") boolean enabled) {
        this.credentials = credentials;
        this.machine = machine;
        this.enabled = enabled;
        if (enabled) {
            credentials.onSwitch("cli-config", key -> writer.execute(() -> apply(key)));
        }
    }

    /** Writes the pool's current entry into the CLI's config (or restores the original when there's none). */
    void apply(String providerKey) {
        if (!enabled) {
            return;
        }
        CredentialService.Choice choice = credentials.current(providerKey);
        try {
            switch (providerKey) {
                case "claude" -> writeClaude(machine.claudeDir(), choice.credential());
                case "codex" -> writeCodex(machine.codexHome(), choice.credential());
                case "qwen" -> writeEnvFile(machine.home(".qwen").resolve(".env"), "OPENAI_API_KEY", "OPENAI_BASE_URL", choice.credential());
                case "gemini" -> writeEnvFile(machine.home(".gemini").resolve(".env"), "GEMINI_API_KEY", "GOOGLE_GEMINI_BASE_URL", choice.credential());
                // GLM shares Claude Code's config (Claude's pool owns it), Kimi's runs have a home of their own;
                // DeepSeek, CodeBuddy and MiniMax have no key file we can write safely: their keys reach runs only.
                default -> { /* nothing machine-wide */ }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("{}: could not update the CLI's config: {}", providerKey, e.getMessage());
        }
    }

    // --- Claude Code: settings.json "env" ---

    void writeClaude(Path dir, RunCredential c) throws IOException {
        Path file = dir.resolve("settings.json");
        ObjectNode root = readObject(file);
        ObjectNode env = root.has("env") && root.get("env").isObject() ? (ObjectNode) root.get("env") : root.putObject("env");
        ObjectNode before = env.deepCopy();
        if (c == null) {
            if (!Files.exists(backupOf(file)) && !Files.exists(absentOf(file))) {
                return; // never written: nothing to undo
            }
            ObjectNode original = readObject(backupOf(file));
            ObjectNode originalEnv = original.has("env") && original.get("env").isObject() ? (ObjectNode) original.get("env") : null;
            for (String var : CLAUDE_VARS) {
                if (originalEnv != null && originalEnv.has(var)) {
                    env.set(var, originalEnv.get(var));
                } else {
                    env.remove(var);
                }
            }
        } else {
            CLAUDE_VARS.forEach(env::remove); // an API key set alongside would win over a token
            if (c.baseUrl() != null) {
                env.put("ANTHROPIC_BASE_URL", c.baseUrl());
            }
            env.put(c.bearer() ? "ANTHROPIC_AUTH_TOKEN" : "ANTHROPIC_API_KEY", c.apiKey());
        }
        if (env.equals(before)) {
            return;
        }
        if (env.isEmpty()) {
            root.remove("env");
        }
        keepOriginal(file);
        writeOwnerOnly(file, mapper.writeValueAsString(root) + "\n");
        log.info("claude: settings.json now uses {}", c == null ? "the original settings" : c);
    }

    // --- Codex: auth.json + config.toml openai_base_url ---

    void writeCodex(Path home, RunCredential c) throws IOException {
        Path auth = home.resolve("auth.json");
        Path config = home.resolve("config.toml");
        if (c == null) {
            boolean restored = restoreWhole(auth);
            String original = Files.exists(backupOf(config)) ? MachineCredentials.openaiBaseUrl(backupOf(config)) : null;
            if (Files.exists(backupOf(config)) || Files.exists(absentOf(config))) {
                restored |= setBaseUrl(config, original);
            }
            if (restored) {
                log.info("codex: config restored to the original settings");
            }
            return;
        }
        ObjectNode wanted = mapper.createObjectNode().put("auth_mode", "apikey").put("OPENAI_API_KEY", c.apiKey());
        boolean changed = false;
        if (!wanted.equals(readObject(auth))) {
            keepOriginal(auth);
            writeOwnerOnly(auth, mapper.writeValueAsString(wanted) + "\n");
            changed = true;
        }
        if (!Objects.equals(MachineCredentials.openaiBaseUrl(config), c.baseUrl())) {
            keepOriginal(config);
            changed |= setBaseUrl(config, c.baseUrl());
        }
        if (changed) {
            log.info("codex: auth.json / config.toml now use {}", c);
        }
    }

    /** Sets (or, with null, removes) the top-level {@code openai_base_url}, leaving the rest of the file as it is. */
    static boolean setBaseUrl(Path config, String url) throws IOException {
        List<String> lines = Files.exists(config) ? new ArrayList<>(Files.readAllLines(config, StandardCharsets.UTF_8))
                : new ArrayList<>();
        int existing = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).strip();
            if (t.startsWith("[")) {
                break; // only top-level keys; tables start here
            }
            if (t.startsWith("openai_base_url")) {
                existing = i;
                break;
            }
        }
        String line = url == null ? null : "openai_base_url = " + new ObjectMapper().writeValueAsString(url);
        if (existing >= 0 && line != null && lines.get(existing).strip().equals(line)) {
            return false;
        }
        if (existing >= 0) {
            if (line == null) {
                lines.remove(existing);
            } else {
                lines.set(existing, line);
            }
        } else if (line != null) {
            lines.add(0, line);
        } else {
            return false;
        }
        writeOwnerOnly(config, String.join("\n", lines) + "\n");
        return true;
    }

    // --- .env files (Qwen, Gemini) ---

    /** Sets the two variables in a CLI's .env, leaving its other lines alone; null restores the file's original. */
    void writeEnvFile(Path file, String keyVar, String urlVar, RunCredential c) throws IOException {
        if (c == null) {
            restoreWhole(file);
            return;
        }
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!l.startsWith(keyVar + "=") && !l.startsWith(urlVar + "=")) {
                    lines.add(l);
                }
            }
        }
        lines.add(keyVar + "=" + c.apiKey());
        if (c.baseUrl() != null) {
            lines.add(urlVar + "=" + c.baseUrl());
        }
        String content = String.join("\n", lines) + "\n";
        if (Files.isRegularFile(file) && content.equals(Files.readString(file))) {
            return;
        }
        keepOriginal(file);
        writeOwnerOnly(file, content);
        log.info("{} now uses {}", file.getFileName(), c);
    }

    // --- originals ---

    private static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + BACKUP);
    }

    private static Path absentOf(Path file) {
        return file.resolveSibling(file.getFileName() + ABSENT);
    }

    /** Once, before SuperAgent first changes a file: a copy of it, or a marker that it didn't exist. */
    private static void keepOriginal(Path file) throws IOException {
        if (Files.exists(backupOf(file)) || Files.exists(absentOf(file))) {
            return;
        }
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            Files.copy(file, backupOf(file), StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            Files.writeString(absentOf(file), "");
        }
    }

    private static boolean restoreWhole(Path file) throws IOException {
        if (Files.exists(backupOf(file))) {
            Files.copy(backupOf(file), file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.delete(backupOf(file));
            return true;
        }
        if (Files.exists(absentOf(file))) {
            Files.deleteIfExists(file);
            Files.delete(absentOf(file));
            return true;
        }
        return false;
    }

    private ObjectNode readObject(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return mapper.createObjectNode();
        }
        var node = mapper.readTree(file.toFile());
        if (node == null || !node.isObject()) {
            throw new IOException(file + " is not a JSON object; left untouched");
        }
        return (ObjectNode) node;
    }

    /** Write-then-rename, owner read/write only: the file holds an API key. */
    private static void writeOwnerOnly(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX filesystem
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @PreDestroy
    void shutdown() {
        writer.shutdownNow();
    }
}
