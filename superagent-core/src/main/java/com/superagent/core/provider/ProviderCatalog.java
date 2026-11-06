package com.superagent.core.provider;

import java.util.List;
import java.util.Optional;

/**
 * Fixed catalogue of mainstream model-vendor CLIs (the runtime engines SuperAgent
 * can call). Static, not user-editable: install/upgrade commands live here in code
 * (never taken from a request), which is what makes the install action safe. Every
 * command is a plain npm install of the vendor's official package — never a piped
 * remote script — and the package names are checked against the vendor's own repo.
 *
 * <p>Three levels: {@code supported} entries (Claude, Codex) are wired into the runtime —
 * tasks and chat run on them, with a live model list and inference checks;
 * {@linkplain Entry#installable() installable} ones can be installed, upgraded and
 * version-checked from the Models page, but nothing runs on them yet; the rest are
 * display-only placeholders. Model lists are deliberately absent here — they are fetched
 * from each CLI / its credentials, because a hard-coded list goes stale in both directions.
 */
public final class ProviderCatalog {

    /**
     * One catalogue entry. {@code authFiles}/{@code authEnvVars} are the heuristic
     * signals for "is authenticated" (presence of a credentials file or env var).
     * {@code minNode} is the package's own {@code engines.node} floor (null = no Node needed);
     * {@code preview} marks a vendor release that isn't stable yet.
     */
    public record Entry(
            String key,
            String name,
            String cli,
            boolean supported,
            String installCommand,
            String upgradeCommand,
            List<String> versionArgs,
            String npmPackage,
            List<String> authFiles,
            List<String> authEnvVars,
            String minNode,
            boolean preview) {

        public boolean installable() {
            return installCommand != null;
        }
    }

    private static Entry npmEntry(String key, String name, String cli, boolean supported, String npmPackage,
                                  String minNode, boolean preview, List<String> authFiles, List<String> authEnvVars) {
        // Install and upgrade are the same command: always the latest published release.
        String latest = "npm install -g " + npmPackage + "@latest --no-fund --no-audit";
        return new Entry(key, name, cli, supported, latest, latest, List.of("--version"), npmPackage, authFiles,
                authEnvVars, minNode, preview);
    }

    private static final List<Entry> ENTRIES = List.of(
            npmEntry("claude", "Claude Code", "claude", true, "@anthropic-ai/claude-code", "22.0.0", false,
                    List.of("~/.claude/.credentials.json", "~/.claude.json"), List.of("ANTHROPIC_API_KEY")),
            npmEntry("codex", "OpenAI Codex", "codex", true, "@openai/codex", "16.0.0", false,
                    List.of("~/.codex/auth.json"), List.of("OPENAI_API_KEY")),
            // --- installable: install / upgrade / version from the Models page; not wired into tasks yet ---
            npmEntry("qwen", "Qwen Code", "qwen", false, "@qwen-code/qwen-code", "22.0.0", false, List.of(), List.of()),
            // Moonshot's maintained CLI; the older Python kimi-cli is archived.
            npmEntry("kimi", "Kimi Code", "kimi", false, "@moonshot-ai/kimi-code", "22.19.0", false, List.of(), List.of()),
            // DeepSeek Harness: an agent harness with a Web UI (dsh web); still a release candidate.
            npmEntry("deepseek", "DeepSeek Harness", "dsh", false, "@deepseek-ai/dsh", "24.9.0", true, List.of(), List.of()),
            // Zhipu ships no agent CLI of its own: this official helper sets up coding tools on the GLM Coding Plan.
            npmEntry("glm", "GLM Coding Helper", "coding-helper", false, "@z_ai/coding-helper", "18.0.0", false,
                    List.of(), List.of()),
            // --- display-only (functionality to be completed later) ---
            new Entry("gemini", "Gemini CLI", "gemini", false, null, null,
                    List.of("--version"), "@google/gemini-cli", List.of(), List.of("GEMINI_API_KEY"), null, false));

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static Optional<Entry> find(String key) {
        return ENTRIES.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    private ProviderCatalog() {
    }
}
