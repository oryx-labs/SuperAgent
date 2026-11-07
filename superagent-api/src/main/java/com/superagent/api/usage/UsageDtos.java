package com.superagent.api.usage;

import java.time.Instant;
import java.util.List;

/**
 * Token usage, the raw cost of everything run on a model. Every count is separate and they add up to {@code total}:
 * {@code input} at full price, {@code cacheRead} served from the cache, {@code cacheWrite} written to it, {@code output}
 * (reasoning included; {@code reasoning} is the part of it). Costs are estimates from list prices, in USD; null when
 * no price is known for a model, and a total with {@code costPartial} leaves such models out.
 */
public final class UsageDtos {

    private UsageDtos() {
    }

    /** One model's tokens in one run. */
    public record Row(Instant at, String source, String user, String agentId, String agentName, String cli, String model,
                      long input, long cacheRead, long cacheWrite, long output, long reasoning, long total,
                      Double costUsd, Double cliCostUsd) {
    }

    /**
     * Tokens added up under {@code key} (a model, a day, a source, a person, a task…); {@code label} is how to show it.
     * {@code models}: the models used, most tokens first; {@code lastAt}: the latest run.
     */
    public record Total(String key, String label, long input, long cacheRead, long cacheWrite, long output,
                        long reasoning, long total, Double costUsd, boolean costPartial, int runs, List<String> models,
                        Instant lastAt) {
    }

    /** A task's usage: every run, oldest first, then totals. */
    public record Task(List<Row> rows, List<Total> byModel, Total total) {
    }

    /**
     * Usage over a period, in its two kinds: {@code tasks} (one row per task, the choice of its model included) and
     * {@code agents} (everything else: chats, the SuperAgent and its background, agents' memory, one row per agent;
     * key "" for what belongs to no agent). {@code byUser} only in the company view.
     */
    public record Summary(Instant from, Instant to, Total total, Total taskTotal, Total agentTotal, List<Total> byDay,
                          List<Total> tasks, List<Total> agents, List<Total> byModel, List<Total> byUser,
                          /**
                           * The system's own background work (checking models and keys), which belongs to nobody:
                           * {@code systemTotal} and one row per kind and model ({@code key} "kind|model"). Company view only.
                           */
                          Total systemTotal, List<Total> system) {
    }
}
