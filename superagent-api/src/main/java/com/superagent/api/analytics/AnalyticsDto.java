package com.superagent.api.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Platform-wide figures for administrators. {@code activeUsers} = users who started a task in the last
 * {@code activeDays} days. {@code successRate} = done / (done + failed), null before any task settled.
 * {@code daily} covers the last {@code days.size()} days, oldest first.
 */
public record AnalyticsDto(
        int users,
        int enabledUsers,
        int activeUsers,
        int activeDays,
        int agents,
        int automations,
        int enabledAutomations,
        int tasks,
        int tasksDone,
        int tasksFailed,
        Double successRate,
        BigDecimal spend,
        List<Day> daily,
        List<ModelUsage> models) {

    public record Day(LocalDate date, int created, int done, int failed) {
    }

    /** {@code model} null = the CLI's own default model. */
    public record ModelUsage(String model, int tasks, BigDecimal spend) {
    }
}
