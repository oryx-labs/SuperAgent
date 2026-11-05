package com.superagent.api.task;

import java.math.BigDecimal;

/**
 * The signed-in user's task overview for the top of the task list: what is running, what waits on them,
 * what finished today, what failed, and this month's spend.
 */
public record TaskStatsDto(
        int total,
        int active,
        int needsConfirmation,
        int doneToday,
        int failed,
        BigDecimal spendThisMonth) {
}
