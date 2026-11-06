package com.superagent.platform.provider;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a run's error means the endpoint or key failed — the cases another key can fix: rejected credentials,
 * exhausted quota or rate limit, the server down or overloaded, the connection failing. A bad request, an unknown
 * model or a tool/sandbox error is not one of them; switching keys wouldn't help, so those fail normally.
 */
public final class EndpointFailures {

    /** An HTTP status reported by the API (as CLIs print it: "API Error: 429 …", "status 503", "HTTP 401"). */
    private static final Pattern STATUS = Pattern.compile(
            "(?i)(api error|status|http|code)[^\\n]{0,16}?\\b(401|402|403|429|500|502|503|504|529)\\b");

    private static final List<String> PHRASES = List.of(
            "invalid api key", "invalid x-api-key", "invalid_api_key", "incorrect api key", "authentication_error",
            "authentication failed", "unauthorized", "forbidden", "api key not valid",
            // Claude Code's wording for a rejected key, even when no OAuth login is involved.
            "failed to authenticate", "oauth session expired", "oauth token has expired",
            "rate limit", "rate_limit", "too many requests", "quota", "insufficient_quota", "credit balance",
            "billing", "额度", "余额不足", "欠费",
            "overloaded", "service unavailable", "bad gateway", "gateway timeout", "internal server error",
            "econnrefused", "econnreset", "enotfound", "etimedout", "connection refused", "connection error",
            "fetch failed", "socket hang up", "could not resolve host", "error sending request", "network error",
            "无法连接");

    private EndpointFailures() {
    }

    public static boolean isEndpointFailure(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        if (STATUS.matcher(error).find()) {
            return true;
        }
        String e = error.toLowerCase(Locale.ROOT);
        return PHRASES.stream().anyMatch(e::contains);
    }
}
