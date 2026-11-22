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
 * @param contextTokens the largest context a single request of the run read (input + cache read + cache write), 0 when
 *                      the CLI doesn't say; a turn that calls tools re-reads its whole context each time, so summing
 *                      the requests would overstate it many times over
 */
public record TokenUsage(String model, long input, long cacheRead, long cacheWrite, long cacheWrite1h, long output,
                         long reasoning, Double cliCostUsd, long contextTokens) {

    public TokenUsage(String model, long input, long cacheRead, long cacheWrite, long cacheWrite1h, long output,
                      long reasoning, Double cliCostUsd) {
        this(model, input, cacheRead, cacheWrite, cacheWrite1h, output, reasoning, cliCostUsd, 0);
    }

    public long total() {
        return input + cacheRead + cacheWrite + output;
    }

    /** Not a bean getter on purpose: it mustn't travel in the JSON. */
    public boolean nothingUsed() {
        return total() == 0;
    }
}
