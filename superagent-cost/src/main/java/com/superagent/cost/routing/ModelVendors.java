package com.superagent.cost.routing;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who makes a model, told from its id (a reseller's "vendor/" prefix aside). A second opinion is worth most from a
 * model trained elsewhere, so a reviewer is picked from another vendor first. Unknown ids fall back to their CLI.
 */
final class ModelVendors {

    private ModelVendors() {
    }

    /** Id prefixes, in order: the first that matches names the vendor. */
    private static final List<Map.Entry<String, String>> PREFIXES = List.of(
            Map.entry("claude", "anthropic"), Map.entry("anthropic", "anthropic"),
            Map.entry("gpt", "openai"), Map.entry("o1", "openai"), Map.entry("o3", "openai"), Map.entry("o4", "openai"),
            Map.entry("codex", "openai"), Map.entry("chatgpt", "openai"),
            Map.entry("glm", "zhipu"), Map.entry("chatglm", "zhipu"),
            Map.entry("kimi", "moonshot"), Map.entry("moonshot", "moonshot"),
            Map.entry("qwen", "alibaba"), Map.entry("qwq", "alibaba"),
            Map.entry("deepseek", "deepseek"), Map.entry("gemini", "google"), Map.entry("grok", "xai"),
            Map.entry("minimax", "minimax"), Map.entry("abab", "minimax"));

    private static final Map<String, String> CLIS = Map.of("claude", "anthropic", "codex", "openai", "glm", "zhipu",
            "kimi", "moonshot", "qwen", "alibaba", "deepseek", "deepseek", "grok", "xai", "gemini", "google");

    static String of(String modelId, String cli) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        id = id.substring(id.lastIndexOf('/') + 1);
        for (Map.Entry<String, String> p : PREFIXES) {
            if (id.startsWith(p.getKey())) {
                return p.getValue();
            }
        }
        String c = cli == null ? "" : cli.toLowerCase(Locale.ROOT);
        return CLIS.getOrDefault(c, c.isEmpty() ? "unknown" : c);
    }
}
