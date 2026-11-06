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

    boolean viaGateway() {
        return !ANTHROPIC.equals(base);
    }

    HttpRequest.Builder authorize(HttpRequest.Builder req) {
        return bearer ? req.header("Authorization", "Bearer " + key) : req.header("x-api-key", key);
    }

    private static String trim(String url, String fallback) {
        return blank(url) ? fallback : url.strip().replaceAll("/+$", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
