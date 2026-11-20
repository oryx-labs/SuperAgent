package com.superagent.runtime.cli;

import com.superagent.core.usage.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what a Claude Code result reports into this turn's tokens and cost. A result's {@code modelUsage} and
 * {@code total_cost_usd} are the conversation's running totals (across {@code --resume} and kept processes), so
 * billing them per turn counts the same history again and again. In order of preference:
 * <ol>
 *   <li>{@code model_usage_since_last_result}, the turn's own usage per model, side calls included (newer CLIs);</li>
 *   <li>the running totals minus the last ones this server saw for the conversation (or minus nothing on its first
 *       turn), which keeps the CLI's side calls (e.g. a cheaper model naming the session) that never show up as
 *       assistant messages;</li>
 *   <li>otherwise (a conversation resumed with no known totals, e.g. after a restart): the turn's API calls as the
 *       assistant messages carried them, and no cost — the side calls' share can't be told apart from the history,
 *       so it is left out rather than billed twice.</li>
 * </ol>
 * The last totals are kept in memory per CLI conversation, shared by ordinary and kept runs.
 */
final class ClaudeUsageLedger {

    /** This turn's tokens, and its cost as the CLI priced it (null when it can't be told). */
    record Turn(List<TokenUsage> tokens, Double costUsd) {
    }

    private record Totals(List<TokenUsage> byModel, Double costUsd) {
        static final Totals NONE = new Totals(List.of(), 0.0);
    }

    private static final int MAX_CONVERSATIONS = 10_000;

    private final Map<String, Totals> last = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Totals> eldest) {
            return size() > MAX_CONVERSATIONS;
        }
    };

    /**
     * This turn's share of a result. {@code newConversation} is true on a conversation's first turn, where the totals
     * are the turn.
     */
    Turn settle(ClaudeStreamParser.ResultUsage usage, boolean newConversation) {
        Totals before;
        synchronized (last) {
            before = newConversation ? Totals.NONE : usage.sessionId() == null ? null : last.get(usage.sessionId());
            if (usage.sessionId() != null && !usage.cumulative().isEmpty()) {
                last.put(usage.sessionId(), new Totals(usage.cumulative(), usage.totalCostUsd()));
            }
        }
        if (usage.sinceLast() != null) {
            return new Turn(usage.sinceLast(), usage.sinceLastCostUsd());
        }
        if (before != null && !usage.cumulative().isEmpty()) {
            Double cost = usage.totalCostUsd() == null || before.costUsd() == null ? null
                    : Math.max(0, usage.totalCostUsd() - before.costUsd());
            return new Turn(minus(usage.cumulative(), before.byModel()), cost);
        }
        return new Turn(usage.calls(), null);
    }

    /**
     * The conversation's next result can't be compared with the last one: a turn ended without a result, and what its
     * calls used was billed from the messages, so a difference from older totals would bill it again.
     */
    void forget(String sessionId) {
        if (sessionId != null) {
            synchronized (last) {
                last.remove(sessionId);
            }
        }
    }

    private static List<TokenUsage> minus(List<TokenUsage> now, List<TokenUsage> before) {
        Map<String, TokenUsage> earlier = new LinkedHashMap<>();
        before.forEach(t -> earlier.put(String.valueOf(t.model()), t));
        List<TokenUsage> out = new ArrayList<>();
        for (TokenUsage t : now) {
            TokenUsage b = earlier.get(String.valueOf(t.model()));
            out.add(b == null ? t : new TokenUsage(t.model(),
                    Math.max(0, t.input() - b.input()), Math.max(0, t.cacheRead() - b.cacheRead()),
                    Math.max(0, t.cacheWrite() - b.cacheWrite()), Math.max(0, t.cacheWrite1h() - b.cacheWrite1h()),
                    Math.max(0, t.output() - b.output()), Math.max(0, t.reasoning() - b.reasoning()),
                    t.cliCostUsd() == null ? null : Math.max(0, t.cliCostUsd() - (b.cliCostUsd() == null ? 0 : b.cliCostUsd()))));
        }
        return out;
    }
}
