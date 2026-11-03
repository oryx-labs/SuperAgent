package com.superagent.core.metrics;

/**
 * Metric names and tag keys shared across SuperAgent.
 *
 * <p>Every application-defined metric name starts with {@link #PREFIX}, so
 * SuperAgent's own series stay easy to separate from framework metrics
 * ({@code jvm_*}, {@code http_*}, {@code process_*}). Tag keys are shared so
 * dashboards and queries can rely on a single spelling.
 */
public final class MetricNames {

    /** Prefix for every application-defined metric. */
    public static final String PREFIX = "superagent.";

    /** Tag identifying the application (process) that emitted a metric. */
    public static final String TAG_APPLICATION = "application";

    private MetricNames() {
    }
}
