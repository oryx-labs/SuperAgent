package com.superagent.task;

import java.util.Locale;

/** Tells errors the API will give again however often the request is repeated from errors that come and go. */
public final class ApiErrors {

    private ApiErrors() {
    }

    /** A client error (HTTP 400): a broken conversation or request, so a retry on the same session fails the same way. */
    public static boolean isClientError(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("invalid_request_error") || m.contains("invalid signature in thinking block")
                || m.matches("(?s).*\\b400\\b.*(bad request|api error|invalid).*");
    }
}
