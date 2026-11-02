package com.superagent.core.logging;

/**
 * Canonical log output format for every SuperAgent process.
 *
 * <p>The pattern is applied to all Spring Boot applications through
 * {@link LoggingEnvironmentPostProcessor}. Changing the layout here changes it
 * for web, server and worker alike.
 *
 * <p>Fields, in order: timestamp, level, thread, trace id (from {@link LogContext}),
 * logger name, message.
 */
public final class LogFormat {

    /** {@code %X{traceId:-}} renders "-" when no trace id is bound. */
    public static final String PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{" + LogContext.TRACE_ID_KEY
                    + ":-}] %logger{36} - %msg%n";

    private LogFormat() {
    }
}
