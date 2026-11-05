package com.superagent.agent.memory;

import java.util.List;
import java.util.Locale;

/**
 * Whether a failed turn says something about the <em>session</em>. Network trouble, timeouts, rate limits
 * and provider outages would fail on any session, so they don't count towards giving one up; anything
 * else (a conversation the CLI can't continue, a context that no longer fits, ...) does.
 */
final class SessionHealth {

    private static final List<String> TRANSIENT = List.of(
            "network", "econnreset", "econnrefused", "etimedout", "enotfound", "eai_again", "socket hang up",
            "connection reset", "connection refused", "connection closed", "fetch failed", "timed out", "timeout",
            "overloaded", "rate limit", "rate_limit", "too many requests", "service unavailable", "bad gateway",
            "gateway timeout", "internal server error", " 429", " 500", " 502", " 503", " 504", " 529");

    static boolean isTransient(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        String e = " " + error.toLowerCase(Locale.ROOT);
        return TRANSIENT.stream().anyMatch(e::contains);
    }

    private SessionHealth() {
    }
}
