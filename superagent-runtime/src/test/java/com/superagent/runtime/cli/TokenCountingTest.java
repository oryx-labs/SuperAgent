package com.superagent.runtime.cli;

import com.superagent.core.usage.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each CLI's tokens come out in one shape, nothing counted twice (Claude Code's: see ClaudeUsageLedgerTest). Codex's lines are copied from real
 * runs; Qwen Code's, DeepSeek Harness's and Kimi Code's follow their sources.
 */
class TokenCountingTest {

    @Test
    void codexReportsInputWithTheCachedPartInsideSoItIsSplit() {
        CodexStreamParser parser = new CodexStreamParser();
        parser.parse("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":20588,\"cached_input_tokens\":20224,"
                + "\"cache_write_input_tokens\":0,\"output_tokens\":69,\"reasoning_output_tokens\":62}}");
        assertThat(parser.tokens("gpt-5.5")).containsExactly(new TokenUsage("gpt-5.5", 364, 20224, 0, 0, 69, 62, null));
        assertThat(parser.tokens("gpt-5.5").get(0).total()).isEqualTo(20588 + 69);
    }

    /** {@code exec resume} reports the thread's running total; the rollout's last total before the run is taken off. */
    @Test
    void aResumedCodexTurnIsBilledOnlyWhatItAdded(@TempDir Path home) throws Exception {
        Path day = Files.createDirectories(home.resolve("sessions/2026/11/19"));
        String thread = "01a1f45e-5b40-7250-a4f1-0376d248f39c";
        Files.writeString(day.resolve("rollout-2026-11-19T23-13-08-" + thread + ".jsonl"), String.join("\n",
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + thread + "\"}}",
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":20886,"
                        + "\"cached_input_tokens\":0,\"output_tokens\":5,\"reasoning_output_tokens\":0}}}}", ""));
        var before = CodexRollouts.lastTotal(home, thread).orElseThrow();

        CodexStreamParser parser = new CodexStreamParser(); // the real second turn's report
        parser.parse("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":41825,\"cached_input_tokens\":20224,"
                + "\"cache_write_input_tokens\":0,\"output_tokens\":10,\"reasoning_output_tokens\":0}}");
        assertThat(parser.tokens("gpt-5.5", before)).containsExactly(new TokenUsage("gpt-5.5", 715, 20224, 0, 0, 5, 0, null));
        assertThat(CodexRollouts.lastTotal(home, "00000000-0000-0000-0000-000000000000")).isEmpty();
    }

    /** The rollout's {@code last_token_usage} is one request's input (cached part included), not a turn's sum. */
    @Test
    void aCodexTurnsContextIsTheRolloutsLastSingleRequest(@TempDir Path home) throws Exception {
        Path day = Files.createDirectories(home.resolve("sessions/2026/11/19"));
        String thread = "01a1f45e-5b40-7250-a4f1-0376d248f39c";
        Files.writeString(day.resolve("rollout-2026-11-19T23-13-08-" + thread + ".jsonl"), String.join("\n",
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":20886,"
                        + "\"cached_input_tokens\":0},\"last_token_usage\":{\"input_tokens\":20886,\"cached_input_tokens\":0}}}}",
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":41825,"
                        + "\"cached_input_tokens\":20224},\"last_token_usage\":{\"input_tokens\":20939,\"cached_input_tokens\":20224}}}}",
                ""));
        assertThat(CodexRollouts.lastRequestInput(home, thread)).contains(20_939L);
        assertThat(CodexRollouts.lastRequestInput(home, "00000000-0000-0000-0000-000000000000")).isEmpty();
    }

    @Test
    void qwenDeepSeekAndKimiCountTheirOwnWay(@TempDir Path home) throws Exception {
        // Qwen Code: input is the whole prompt, cache reads included.
        JsonLineCliRunner.LineParser qwen = new QwenCliAgentRunner("qwen", 60).newParser();
        qwen.parse("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\","
                + "\"usage\":{\"input_tokens\":5000,\"output_tokens\":300,\"cache_read_input_tokens\":4000,\"total_tokens\":5300}}");
        assertThat(qwen.tokens("qwen3-coder-plus")).containsExactly(new TokenUsage("qwen3-coder-plus", 1000, 4000, 0, 0, 300, 0, null));

        // DeepSeek Harness: one usage per step, input without the cache.
        JsonLineCliRunner.LineParser dsh = new DeepSeekCliAgentRunner("dsh", 60).newParser();
        dsh.parse("{\"type\":\"status\",\"phase\":\"step_end\",\"turn\":1,\"step\":1,\"usage\":{\"inputTokens\":800,\"outputTokens\":90,"
                + "\"cacheReadTokens\":6000,\"cacheWriteTokens\":0,\"reasoningTokens\":40,\"totalTokens\":6890}}");
        dsh.parse("{\"type\":\"status\",\"phase\":\"step_end\",\"turn\":1,\"step\":2,\"usage\":{\"inputTokens\":200,\"outputTokens\":10}}");
        dsh.parse("{\"type\":\"status\",\"phase\":\"step_start\",\"turn\":1,\"step\":3}");
        assertThat(dsh.tokens("deepseek-v4")).containsExactly(new TokenUsage("deepseek-v4", 1000, 6000, 0, 0, 100, 40, null));

        // Kimi Code prints no usage: what this session appended to its wire logs counts, nobody else's.
        Path mine = Files.createDirectories(home.resolve("sessions/ws1/sess-A/agents/main"));
        Path other = Files.createDirectories(home.resolve("sessions/ws1/sess-B/agents/main"));
        String msg = "{\"type\":\"context.append_message\",\"agentId\":\"main\",\"message\":{\"role\":\"%s\",\"content\":\"x\","
                + "\"usage\":{\"inputOther\":%d,\"inputCacheRead\":1000,\"inputCacheCreation\":0,\"output\":20}}}\n";
        Files.writeString(mine.resolve("wire.jsonl"), msg.formatted("assistant", 1)); // an earlier turn
        Map<Path, Long> before = KimiCliAgentRunner.wireSizes(home);
        Files.writeString(mine.resolve("wire.jsonl"), msg.formatted("assistant", 50) + msg.formatted("assistant", 70)
                + msg.formatted("user", 999), java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(other.resolve("wire.jsonl"), msg.formatted("assistant", 5000));
        assertThat(KimiCliAgentRunner.appendedUsage(home, "sess-A", before, "kimi-k2"))
                .isEqualTo(new TokenUsage("kimi-k2", 120, 2000, 0, 0, 40, 0, null));
    }
}
