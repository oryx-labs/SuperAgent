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
 * <p>Two levels: {@code supported} entries are wired into the runtime — tasks and chat
 * run on them, with a live model list and inference checks; the rest are install-only:
 * install, upgrade, version and API keys from the Models page, but nothing runs on them
 * yet. Model lists are deliberately absent here — they are fetched from each CLI / its
 * credentials, because a hard-coded list goes stale in both directions.
 */
public final class ProviderCatalog {

    /** How a vendor's API is reached: its wire protocol, default endpoint and environment variables. */
    public record Access(String protocol, String defaultBaseUrl, String keyEnv, String baseUrlEnv) {
    }

    public static final String ANTHROPIC = "anthropic";
    public static final String OPENAI = "openai";
    public static final String OTHER = "other";

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
            boolean preview,
            Access access) {

        public boolean installable() {
            return installCommand != null;
        }
    }

    private static Entry npmEntry(String key, String name, String cli, boolean supported, String npmPackage,
                                  String minNode, boolean preview, List<String> authFiles, Access access) {
        // Install and upgrade are the same command: always the latest published release.
        String latest = "npm install -g " + npmPackage + "@latest --no-fund --no-audit";
        return new Entry(key, name, cli, supported, latest, latest, List.of("--version"), npmPackage, authFiles,
                access.keyEnv() == null ? List.of() : List.of(access.keyEnv()), minNode, preview, access);
    }

    private static final List<Entry> ENTRIES = List.of(
            npmEntry("claude", "Claude Code", "claude", true, "@anthropic-ai/claude-code", "22.0.0", false,
                    List.of("~/.claude/.credentials.json", "~/.claude.json"),
                    new Access(ANTHROPIC, "https://api.anthropic.com", "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL")),
            npmEntry("codex", "OpenAI Codex", "codex", true, "@openai/codex", "16.0.0", false,
                    List.of("~/.codex/auth.json"), new Access(OPENAI, "https://api.openai.com/v1", "OPENAI_API_KEY", null)),
            // DeepSeek Harness: an agent harness; still a release candidate. Speaks Anthropic's protocol.
            npmEntry("deepseek", "DeepSeek Harness", "dsh", true, "@deepseek-ai/dsh", "24.9.0", true, List.of(),
                    new Access(ANTHROPIC, "https://api.deepseek.com/anthropic", "DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL")),
            // Zhipu ships no agent CLI: GLM runs on Claude Code against Zhipu's Anthropic-compatible endpoint.
            npmEntry("glm", "GLM（智谱）", "claude", true, "@anthropic-ai/claude-code", "22.0.0", false, List.of(),
                    new Access(ANTHROPIC, "https://open.bigmodel.cn/api/anthropic", "ZHIPU_API_KEY", null)),
            // Moonshot's maintained CLI; the older Python kimi-cli is archived.
            npmEntry("kimi", "Kimi Code", "kimi", true, "@moonshot-ai/kimi-code", "22.19.0", false, List.of(),
                    new Access(OPENAI, "https://api.moonshot.cn/v1", "KIMI_API_KEY", "KIMI_CODE_BASE_URL")),
            npmEntry("qwen", "Qwen Code", "qwen", true, "@qwen-code/qwen-code", "22.0.0", false, List.of(),
                    new Access(OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", null)),
            // --- install-only: install / upgrade / version / keys from the Models page; not wired into tasks yet ---
            npmEntry("codebuddy", "CodeBuddy Code（腾讯）", "codebuddy", false, "@tencent-ai/codebuddy-code", "18.0.0", false,
                    List.of(), new Access(OTHER, null, "CODEBUDDY_API_KEY", "CODEBUDDY_BASE_URL")),
            npmEntry("minimax", "MiniMax Code", "mcode", false, "@minimax-ai/code", "22.19.0", false,
                    List.of(), new Access(OTHER, null, "MINIMAX_API_KEY", "MCODE_API_BASE_URL")),
            npmEntry("gemini", "Gemini CLI", "gemini", false, "@google/gemini-cli", "20.0.0", false,
                    List.of(), new Access(OTHER, null, "GEMINI_API_KEY", "GOOGLE_GEMINI_BASE_URL")));

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static Optional<Entry> find(String key) {
        return ENTRIES.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    private ProviderCatalog() {
    }
}
