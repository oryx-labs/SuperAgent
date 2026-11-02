package com.superagent.core.logging;

import org.slf4j.MDC;

/**
 * Binds per-request context into SLF4J's MDC so every log line carries it.
 *
 * <p>{@link LogFormat#PATTERN} reads the trace id from here. Call
 * {@link #setTraceId(String)} when a request or job starts and
 * {@link #clear()} when it ends, otherwise the id leaks into unrelated log lines
 * on the same pooled thread.
 */
public final class LogContext {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String USER_ID_KEY = "userId";

    private LogContext() {
    }

    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    public static String getTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    public static void setUserId(String userId) {
        MDC.put(USER_ID_KEY, userId);
    }

    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
        MDC.remove(USER_ID_KEY);
    }
}
