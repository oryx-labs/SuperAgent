package com.superagent.core.provider;

import java.util.List;
import java.util.Optional;

/**
 * Fixed catalogue of mainstream model-vendor CLIs (the runtime engines SuperAgent
 * can call). Static, not user-editable: install/upgrade commands live here in code
 * (never taken from a request), which is what makes the install action safe.
 *
 * <p>Only {@code supported} entries (Claude, Codex) have real install/version/auth
 * wiring and a live model list; the rest are display-only placeholders. Model lists
 * are deliberately absent here — they are fetched from each CLI / its credentials,
 * because a hard-coded list goes stale in both directions.
 */
public final class ProviderCatalog {

    /**
     * One catalogue entry. {@code authFiles}/{@code authEnvVars} are the heuristic
     * signals for "is authenticated" (presence of a credentials file or env var).
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
            List<String> authEnvVars) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("claude", "Claude Code", "claude", true,
                    "npm install -g @anthropic-ai/claude-code",
                    "npm install -g @anthropic-ai/claude-code@latest",
                    List.of("--version"), "@anthropic-ai/claude-code",
                    List.of("~/.claude/.credentials.json", "~/.claude.json"), List.of("ANTHROPIC_API_KEY")),
            new Entry("codex", "OpenAI Codex", "codex", true,
                    "npm install -g @openai/codex",
                    "npm install -g @openai/codex@latest",
                    List.of("--version"), "@openai/codex",
                    List.of("~/.codex/auth.json"), List.of("OPENAI_API_KEY")),
            // --- display-only (functionality to be completed later) ---
            new Entry("gemini", "Gemini CLI", "gemini", false, null, null,
                    List.of("--version"), "@google/gemini-cli", List.of(), List.of("GEMINI_API_KEY")),
            new Entry("qwen", "Qwen Code", "qwen", false, null, null,
                    List.of("--version"), "@qwen-code/qwen-code", List.of(), List.of()),
            new Entry("glm", "GLM (Zhipu)", "", false, null, null, List.of(), null, List.of(), List.of()),
            new Entry("kimi", "Kimi (Moonshot)", "", false, null, null, List.of(), null, List.of(), List.of()),
            new Entry("deepseek", "DeepSeek", "", false, null, null, List.of(), null, List.of(), List.of()));

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static Optional<Entry> find(String key) {
        return ENTRIES.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    private ProviderCatalog() {
    }
}
