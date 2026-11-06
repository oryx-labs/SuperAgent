package com.superagent.platform.provider;

import com.superagent.core.chat.RunCredential;

import java.net.http.HttpRequest;
import java.util.Optional;

/**
 * The Anthropic endpoint + key an HTTP call (model list, inference check) uses: a pool credential when one is
 * given, else the server's environment — the same order the runs themselves follow. Empty when there is no key
 * at all (a subscription login, which only the CLI can use).
 */
record ApiAccess(String base, String key, boolean bearer) {

    static final String ANTHROPIC = "https://api.anthropic.com";

    static Optional<ApiAccess> anthropic(RunCredential credential) {
        if (credential != null) {
            return Optional.of(new ApiAccess(trim(credential.baseUrl(), ANTHROPIC), credential.apiKey(), credential.bearer()));
        }
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String token = System.getenv("ANTHROPIC_AUTH_TOKEN");
        if (blank(apiKey) && blank(token)) {
            return Optional.empty();
        }
        return Optional.of(new ApiAccess(trim(System.getenv("ANTHROPIC_BASE_URL"), ANTHROPIC),
                blank(apiKey) ? token : apiKey, blank(apiKey)));
    }

    /** A vendor reached through a pool credential; its endpoint falls back to the catalogue's default. */
    static Optional<ApiAccess> of(com.superagent.core.provider.ProviderCatalog.Entry entry, RunCredential credential) {
        if (credential == null) {
            return Optional.empty();
        }
        return Optional.of(new ApiAccess(trim(credential.baseUrl(), entry.access().defaultBaseUrl()), credential.apiKey(),
                credential.bearer()));
    }

    /** Where this endpoint lists models: its OpenAI-style API, which the Anthropic-compatible vendors keep beside it. */
    String modelsUrl(String providerKey) {
        return switch (providerKey) {
            case "deepseek" -> base.replaceFirst("/anthropic$", "") + "/models";
            case "glm" -> base.replaceFirst("/api/anthropic$", "/api/paas/v4") + "/models";
            default -> base + "/models";
        };
    }

    boolean viaGateway() {
        return !ANTHROPIC.equals(base);
    }

    HttpRequest.Builder authorize(HttpRequest.Builder req) {
        return bearer ? req.header("Authorization", "Bearer " + key) : req.header("x-api-key", key);
    }

    /** OpenAI-style auth, whatever this entry's own header choice. */
    HttpRequest.Builder authorizeBearer(HttpRequest.Builder req) {
        return req.header("Authorization", "Bearer " + key);
    }

    private static String trim(String url, String fallback) {
        return blank(url) ? fallback : url.strip().replaceAll("/+$", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
