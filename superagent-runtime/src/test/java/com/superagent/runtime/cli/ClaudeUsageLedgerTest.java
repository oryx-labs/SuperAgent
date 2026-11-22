package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.usage.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A Claude Code result reports the conversation's running totals; each turn is billed only its own share. The lines
 * follow a real two-turn conversation (the second turn {@code --resume}d), trimmed to the fields read.
 */
class ClaudeUsageLedgerTest {

    private static final String TURN1 = "{\"type\":\"result\",\"is_error\":false,\"result\":\"one\",\"session_id\":\"c7\","
            + "\"total_cost_usd\":0.0441568,\"modelUsage\":{\"claude-sonnet-5-5\":{\"inputTokens\":8,\"outputTokens\":3,"
            + "\"cacheReadInputTokens\":10908,\"cacheCreationInputTokens\":17208,\"costUSD\":0.0441568}}}";
    private static final String CALL_A = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_A\",\"model\":\"claude-sonnet-5-5\","
            + "\"usage\":{\"input_tokens\":8,\"cache_read_input_tokens\":28116,\"cache_creation_input_tokens\":32,\"output_tokens\":%d}}}";
    // A relay may name the model its own way in messages; the result's keys stay the canonical ids.
    private static final String CALL_B = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_B\",\"model\":\"claude-sonnet-5-5\","
            + "\"usage\":{\"input_tokens\":2,\"cache_read_input_tokens\":11148,\"cache_creation_input_tokens\":17250,\"output_tokens\":3}}}";
    private static final String TURN2_TOTALS = "\"session_id\":\"c7\",\"total_cost_usd\":0.0920782,\"modelUsage\":{\"claude-sonnet-5-5\":"
            + "{\"inputTokens\":18,\"outputTokens\":80,\"cacheReadInputTokens\":50172,\"cacheCreationInputTokens\":34490,\"costUSD\":0.0920782}}";
    private static final String TURN2 = "{\"type\":\"result\",\"is_error\":false,\"result\":\"two\"," + TURN2_TOTALS + "}";
    private static final String TURN2_SINCE_LAST = "{\"type\":\"result\",\"is_error\":false,\"result\":\"two\"," + TURN2_TOTALS
            + ",\"model_usage_since_last_result\":{\"claude-sonnet-5-5\":{\"inputTokens\":10,\"outputTokens\":77,"
            + "\"cacheReadInputTokens\":39264,\"cacheCreationInputTokens\":17282,\"costUSD\":0.0479214}}}";

    private static final TokenUsage TURN2_USED = new TokenUsage("claude-sonnet-5-5", 10, 39264, 17282, 0, 77, 0, null);

    private static ClaudeStreamParser.ResultUsage result(String... lines) {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        ClaudeStreamParser.ResultUsage usage = null;
        for (String line : lines) {
            var e = parser.parse(line);
            if (e.isPresent() && e.get().usage() != null) {
                usage = e.get().usage();
            }
        }
        return usage;
    }

    private static String counts(List<TokenUsage> used) {
        return used.stream().map(t -> t.model() + " " + t.input() + "/" + t.cacheRead() + "/" + t.cacheWrite() + "/" + t.output())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    @Test
    void aResumedTurnIsBilledItsOwnShareAndTheTurnsAddUpToTheCliTotal() {
        ClaudeUsageLedger ledger = new ClaudeUsageLedger();
        var first = ledger.settle(result(TURN1), true);
        assertThat(counts(first.tokens())).isEqualTo("claude-sonnet-5-5 8/10908/17208/3");
        assertThat(first.costUsd()).isCloseTo(0.0441568, within(1e-9));

        // An older CLI with no per-turn figure: the difference from the totals the first turn reported.
        var second = ledger.settle(result(CALL_A.formatted(74), CALL_B, TURN2), false);
        assertThat(counts(second.tokens())).isEqualTo("claude-sonnet-5-5 10/39264/17282/77");
        assertThat(second.costUsd()).isCloseTo(0.0479214, within(1e-9));
        assertThat(first.tokens().get(0).output() + second.tokens().get(0).output()).isEqualTo(80); // the CLI's total
        assertThat(first.tokens().get(0).cacheRead() + second.tokens().get(0).cacheRead()).isEqualTo(50172);

        // A newer CLI says what the turn used itself: taken as is, whatever this server remembers (here nothing).
        var reported = new ClaudeUsageLedger().settle(result(CALL_A.formatted(74), CALL_B, TURN2_SINCE_LAST), false);
        assertThat(counts(reported.tokens())).isEqualTo("claude-sonnet-5-5 10/39264/17282/77");
        assertThat(reported.costUsd()).isCloseTo(0.0479214, within(1e-9));
    }

    @Test
    void withNoKnownTotalsAResumedTurnIsBilledItsCallsEachOnceAndNoCost() {
        // e.g. the first turn after a restart: the totals include history this server can't separate out.
        var turn = new ClaudeUsageLedger().settle(result(CALL_A.formatted(1), CALL_B, CALL_A.formatted(74), TURN2), false);
        assertThat(turn.tokens()).containsExactly(TURN2_USED); // msg_A's last usage, counted once
        assertThat(turn.costUsd()).isNull();
    }

    /** A turn's context is its largest request, not the sum over requests (each tool call re-reads the conversation). */
    @Test
    void theTurnsContextIsItsLargestSingleRequest() {
        var usage = result(CALL_A.formatted(3), CALL_B, TURN1);
        // CALL_A read 8 + 28116 + 32, CALL_B 2 + 11148 + 17250 (more): the larger one is the turn's context.
        assertThat(usage.contextTokens()).isEqualTo(28_400);
    }

    /** No assistant message seen (the CLI failed before streaming): the result's own call is the context. */
    @Test
    void withoutAssistantMessagesTheResultCallIsTheContext() {
        var usage = result("{\"type\":\"result\",\"is_error\":false,\"result\":\"one\",\"session_id\":\"c7\","
                + "\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":9000,\"cache_creation_input_tokens\":1000,"
                + "\"output_tokens\":4}}");
        assertThat(usage.calls()).hasSize(1);
        assertThat(usage.contextTokens()).isEqualTo(10_012);
    }

    @Test
    void aSideCallSeenOnlyInTheTotalsIsBilledInFullFirstThenByDifferenceAndNotWithoutABaseline() {
        String side = "{\"type\":\"result\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"c9\",\"total_cost_usd\":%s,"
                + "\"modelUsage\":{\"claude-sonnet-5-5\":{\"inputTokens\":%d,\"outputTokens\":%d},"
                + "\"claude-haiku-4-5\":{\"inputTokens\":%d,\"outputTokens\":%d}}}";
        ClaudeUsageLedger ledger = new ClaudeUsageLedger();
        var first = ledger.settle(result(side.formatted("0.5", 10, 5, 900, 8)), true);
        var second = ledger.settle(result(side.formatted("0.75", 30, 9, 900, 8)), false);
        assertThat(counts(first.tokens())).isEqualTo("claude-sonnet-5-5 10/0/0/5, claude-haiku-4-5 900/0/0/8");
        assertThat(counts(second.tokens())).isEqualTo("claude-sonnet-5-5 20/0/0/4, claude-haiku-4-5 0/0/0/0");
        assertThat(second.costUsd()).isEqualTo(0.25);

        // With no known totals (e.g. after a restart) the side call can't be told apart from history: not billed at all,
        // only the turn's own call (the assistant message) is.
        String call = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_S\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":20,\"output_tokens\":4}}}";
        var unknown = new ClaudeUsageLedger().settle(result(call, side.formatted("0.75", 30, 9, 900, 8)), false);
        assertThat(counts(unknown.tokens())).isEqualTo("claude-sonnet-5-5 20/0/0/4");
        assertThat(unknown.costUsd()).isNull();
    }

    @Test
    void aTurnStoppedBeforeItsResultIsBilledFromItsCallsAndTheNextResultDoesNotBillThemAgain() {
        ClaudeUsageLedger ledger = new ClaudeUsageLedger();
        ledger.settle(result(TURN1), true);

        ClaudeStreamParser stopped = new ClaudeStreamParser();
        stopped.parse(CALL_A.formatted(1));
        stopped.parse(CALL_A.formatted(74)); // the same call again, further on: the last one stands
        stopped.parse(CALL_B);
        assertThat(stopped.unreportedTokens()).containsExactly(TURN2_USED);
        ledger.forget("c7");

        // The next turn's totals include the stopped turn: not measured from the totals before it.
        String call = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_C\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":4,\"output_tokens\":6}}}";
        var next = ledger.settle(result(call, TURN2), false);
        assertThat(counts(next.tokens())).isEqualTo("claude-sonnet-5-5 4/0/0/6");
        assertThat(next.costUsd()).isNull();

        ClaudeStreamParser finished = new ClaudeStreamParser();
        finished.parse(CALL_B);
        finished.parse(TURN2);
        assertThat(finished.unreportedTokens()).isEmpty(); // the result counted it
    }

    /**
     * Ordinary runs, a process each, through a fake {@code claude} whose results carry the conversation's running
     * totals (each turn one call of 100 in / 10 out, costing 1): every turn is billed 100/10 and 1, never the totals.
     */
    @Test
    void eachOrdinaryRunIsBilledOnlyItsTurn(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("claude");
        Path count = dir.resolve("turns");
        Files.writeString(count, "0");
        Files.writeString(fake, String.join("\n",
                "#!/bin/sh",
                "n=$(($(cat '" + count + "')+1)); echo $n > '" + count + "'",
                "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\"}'",
                "echo '{\"type\":\"assistant\",\"message\":{\"id\":\"msg_'$n'\",\"model\":\"sonnet\",\"usage\":{\"input_tokens\":100,\"output_tokens\":10}}}'",
                "echo '{\"type\":\"result\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s1\",\"total_cost_usd\":'$n',"
                        + "\"modelUsage\":{\"sonnet\":{\"inputTokens\":'$((n*100))',\"outputTokens\":'$((n*10))'}}}'", ""));
        fake.toFile().setExecutable(true);

        ClaudeCliAgentRunner runner = new ClaudeCliAgentRunner(fake.toString(), 30);
        List<String> billed = new ArrayList<>();
        List<String> costs = new ArrayList<>();
        java.util.function.Consumer<AgentEvent> sink = e -> {
            if (e.type() == AgentEventType.USAGE) {
                costs.add(e.payload());
            } else if (e.type() == AgentEventType.TOKENS) {
                Tokens.parse(e.payload()).forEach(t -> billed.add(t.input() + "/" + t.output()));
            }
        };
        runner.run(new AgentRunRequest("s1", null, dir.toString(), "hi", "sonnet", List.of(), false, null, null),
                sink::accept, new Cancellation());
        AgentRunRequest resume = new AgentRunRequest("s1", "s1", dir.toString(), "more", "sonnet", List.of(), false, null, null);
        runner.run(resume, sink::accept, new Cancellation());
        runner.run(resume, sink::accept, new Cancellation());
        // After a restart nothing is known of the totals: the turn's own call, and no cost.
        new ClaudeCliAgentRunner(fake.toString(), 30).run(resume, sink::accept, new Cancellation());

        assertThat(billed).containsExactly("100/10", "100/10", "100/10", "100/10");
        assertThat(costs).containsExactly("1.0", "1.0", "1.0");
    }
}
