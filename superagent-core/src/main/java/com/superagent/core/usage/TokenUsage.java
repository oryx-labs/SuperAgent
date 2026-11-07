package com.superagent.core.usage;

/**
 * The tokens one run spent on one model, in a single shape whatever the CLI: every count is separate, so they add up
 * to everything billed and nothing is counted twice.
 *
 * @param input        input tokens read at full price (not from the cache)
 * @param cacheRead    input tokens served from the cache (billed at a fraction)
 * @param cacheWrite   input tokens written to the cache (billed above the input price)
 * @param cacheWrite1h of {@code cacheWrite}, those kept for an hour (dearer still); 0 when the CLI doesn't say
 * @param output       output tokens, thinking included
 * @param reasoning    of {@code output}, the thinking / reasoning tokens (already counted there)
 * @param cliCostUsd   what the CLI itself says the run cost, when it says (Claude Code); null otherwise
 */
public record TokenUsage(String model, long input, long cacheRead, long cacheWrite, long cacheWrite1h, long output,
                         long reasoning, Double cliCostUsd) {

    public long total() {
        return input + cacheRead + cacheWrite + output;
    }

    /** Not a bean getter on purpose: it mustn't travel in the JSON. */
    public boolean nothingUsed() {
        return total() == 0;
    }
}
