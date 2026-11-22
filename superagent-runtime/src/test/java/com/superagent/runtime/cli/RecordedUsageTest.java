package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.usage.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Billing replayed from real runs (test resources {@code usage/}, recorded with Claude Code 2.1.318 and codex-cli
 * 0.167.0, trimmed to the usage fields): a new conversation's turn, its {@code --resume}d second turn, a kept CLI started
 * on that conversation for two more turns, a call repeated per content block, and a resumed Codex thread.
 */
class RecordedUsageTest {

    private static final String SONNET = "claude-sonnet-5-5";

    private static Path recording(String name) throws Exception {
        return Path.of(Objects.requireNonNull(RecordedUsageTest.class.getResource("/usage/" + name)).toURI());
    }

    private static List<String> lines(String name) throws Exception {
        return Files.readAllLines(recording(name), StandardCharsets.UTF_8);
    }

    /** The result events of a recording, in order. */
    private static List<ClaudeStreamParser.ResultUsage> results(String name) throws Exception {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        List<ClaudeStreamParser.ResultUsage> out = new ArrayList<>();
        for (String line : lines(name)) {
            parser.parse(line).map(ClaudeStreamParser.ParsedEvent::usage).ifPresent(out::add);
        }
        return out;
    }

    /** The same result as an older CLI would report it, without the per-turn figure. */
    private static ClaudeStreamParser.ResultUsage withoutSinceLast(ClaudeStreamParser.ResultUsage u) {
        return new ClaudeStreamParser.ResultUsage(u.sessionId(), null, null, u.cumulative(), u.totalCostUsd(), u.calls(),
                u.contextTokens());
    }

    private static TokenUsage only(ClaudeUsageLedger.Turn turn) {
        assertThat(turn.tokens()).hasSize(1);
        return turn.tokens().get(0);
    }

    @Test
    void aResumedConversationsTurnsAddUpToTheCliTotalInsteadOfRepeatingIt() throws Exception {
        var turn1 = results("claude-turn1-new.jsonl").get(0);
        var turn2 = results("claude-turn2-resume.jsonl").get(0);
        // What the CLI reports on the resumed turn is the running total: turn 1 plus turn 2.
        assertThat(turn2.cumulative()).containsExactly(new TokenUsage(SONNET, 18, 50172, 34490, 0, 80, 0, 0.0920782));
        assertThat(turn2.totalCostUsd()).isEqualTo(0.0920782);

        for (boolean newerCli : new boolean[]{true, false}) {
            ClaudeUsageLedger ledger = new ClaudeUsageLedger();
            TokenUsage first = only(ledger.settle(newerCli ? turn1 : withoutSinceLast(turn1), true));
            var second = ledger.settle(newerCli ? turn2 : withoutSinceLast(turn2), false);
            TokenUsage s = only(second);
            assertThat(first).extracting(TokenUsage::input, TokenUsage::cacheRead, TokenUsage::cacheWrite, TokenUsage::output)
                    .containsExactly(8L, 10908L, 17208L, 3L);
            assertThat(s).extracting(TokenUsage::input, TokenUsage::cacheRead, TokenUsage::cacheWrite, TokenUsage::output)
                    .containsExactly(10L, 39264L, 17282L, 77L);
            assertThat(first.input() + s.input()).isEqualTo(18);
            assertThat(first.cacheRead() + s.cacheRead()).isEqualTo(50172);
            assertThat(first.cacheWrite() + s.cacheWrite()).isEqualTo(34490);
            assertThat(first.output() + s.output()).isEqualTo(80);
            assertThat(second.costUsd()).isCloseTo(0.0920782 - 0.0441568, within(1e-9));
        }

        // The turn's own calls (one of them named by a relay alias) say the same; it's what's billed with no known totals.
        TokenUsage calls = only(new ClaudeUsageLedger().settle(withoutSinceLast(turn2), false));
        assertThat(calls).isEqualTo(new TokenUsage(SONNET, 10, 39264, 17282, 0, 77, 0, null));
    }

    @Test
    void aKeptCliStartedOnAResumedConversationBillsEachTurnItsOwn() throws Exception {
        var results = results("claude-kept-resumed-2turns.jsonl");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).cumulative().get(0).output()).isEqualTo(83); // 80 before this process, 3 now

        ClaudeUsageLedger ledger = new ClaudeUsageLedger(); // nothing known: as after a restart
        var first = ledger.settle(results.get(0), false);
        var second = ledger.settle(results.get(1), false);
        assertThat(only(first)).extracting(TokenUsage::input, TokenUsage::cacheRead, TokenUsage::cacheWrite, TokenUsage::output)
                .containsExactly(2L, 28398L, 47L, 3L);
        assertThat(only(second)).extracting(TokenUsage::input, TokenUsage::cacheRead, TokenUsage::cacheWrite, TokenUsage::output)
                .containsExactly(8L, 0L, 28291L, 3L);
        assertThat(first.costUsd() + second.costUsd()).isCloseTo(0.16584300000000002 - 0.0920782, within(1e-9));
    }

    @Test
    void aCallRepeatedPerContentBlockIsCountedOnce() throws Exception {
        var result = results("claude-repeated-message.jsonl").get(0);
        assertThat(lines("claude-repeated-message.jsonl")).filteredOn(l -> l.contains("eFGZuum1")).hasSize(2);
        assertThat(result.calls()).singleElement().extracting(TokenUsage::output).isEqualTo(94L); // 91 + 3, not 91 + 91 + 3
        assertThat(result.calls().get(0).output()).isEqualTo(result.sinceLast().get(0).output());
        // The repeated message carries the same request once; its context is that request, not the sum of both.
        assertThat(result.contextTokens()).isEqualTo(28_557); // 2 + 28457 + 98, the second request's context
    }

    /** The recordings through the runner itself: a fake {@code claude} replays turn 1, then turn 2 on --resume. */
    @Test
    void theRunnerRecordsEachRecordedTurnOnlyOnce(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("claude");
        Files.writeString(fake, String.join("\n",
                "#!/bin/sh",
                "case \"$*\" in",
                "  *--resume*) cat '" + recording("claude-turn2-resume.jsonl") + "' ;;",
                "  *) cat '" + recording("claude-turn1-new.jsonl") + "' ;;",
                "esac", ""));
        fake.toFile().setExecutable(true);
        ClaudeCliAgentRunner runner = new ClaudeCliAgentRunner(fake.toString(), 30);
        List<TokenUsage> billed = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        java.util.function.Consumer<AgentEvent> sink = e -> {
            if (e.type() == AgentEventType.TOKENS) {
                billed.addAll(Tokens.parse(e.payload()));
            } else if (e.type() == AgentEventType.USAGE) {
                costs.add(Double.valueOf(e.payload()));
            }
        };
        String conversation = "c7b19a0e-a963-402d-8019-8807714352b9";
        runner.run(new AgentRunRequest("s1", null, dir.toString(), "one", "sonnet", List.of(), false, null, null),
                sink::accept, new Cancellation());
        runner.run(new AgentRunRequest("s1", conversation, dir.toString(), "two", "sonnet", List.of(), false, null, null),
                sink::accept, new Cancellation());

        assertThat(billed).extracting(TokenUsage::output).containsExactly(3L, 77L);
        assertThat(billed.stream().mapToLong(TokenUsage::cacheRead).sum()).isEqualTo(50172);
        assertThat(costs.get(0) + costs.get(1)).isCloseTo(0.0920782, within(1e-9));
    }

    @Test
    void aResumedCodexTurnIsBilledWhatItAddedToTheThread(@TempDir Path home) throws Exception {
        String thread = "01a1f45e-5b40-7250-a4f1-0376d248f39c";
        Path day = Files.createDirectories(home.resolve("sessions/2026/11/19"));
        // The rollout as it stood before the second turn: the first turn's token_count only.
        Files.writeString(day.resolve("rollout-2026-11-19T23-13-08-" + thread + ".jsonl"),
                lines("codex-rollout-token-counts.jsonl").get(0) + "\n");

        CodexStreamParser first = new CodexStreamParser();
        lines("codex-turn1-new.jsonl").forEach(first::parse);
        CodexStreamParser second = new CodexStreamParser();
        lines("codex-turn2-resume.jsonl").forEach(second::parse);
        TokenUsage t1 = first.tokens("gpt").get(0);
        TokenUsage t2 = second.tokens("gpt", CodexRollouts.lastTotal(home, thread).orElseThrow()).get(0);

        assertThat(second.tokens("gpt").get(0).output()).isEqualTo(10);     // as reported: the thread's total
        assertThat(t1.output() + t2.output()).isEqualTo(10);                  // 5 + 5
        assertThat(t1.total() + t2.total()).isEqualTo(41825 + 10);
        // The rollout's own per-turn figure for the second turn agrees: 20939 in (20224 cached), 5 out.
        assertThat(t2).isEqualTo(new TokenUsage("gpt", 20939 - 20224, 20224, 0, 0, 5, 0, null));
    }
}
