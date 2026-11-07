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
 * Each CLI's tokens come out in one shape, nothing counted twice. Claude Code's and Codex's lines are copied from real
 * runs; Qwen Code's, DeepSeek Harness's and Kimi Code's follow their sources.
 */
class TokenCountingTest {

    @Test
    void claudeCountsTheWholeRunPerModelAndStillCountsARunStoppedBeforeItsResult() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        String result = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"total_cost_usd\":0.0458055,"
                + "\"usage\":{\"input_tokens\":10,\"cache_creation_input_tokens\":35266,\"cache_read_input_tokens\":0,\"output_tokens\":155,"
                + "\"output_tokens_details\":{\"thinking_tokens\":149},\"cache_creation\":{\"ephemeral_1h_input_tokens\":0,\"ephemeral_5m_input_tokens\":35266}},"
                + "\"modelUsage\":{\"claude-haiku-4-5\":{\"inputTokens\":908,\"outputTokens\":163,\"cacheReadInputTokens\":0,"
                + "\"cacheCreationInputTokens\":35266,\"costUSD\":0.0458055,\"thinkingTokens\":149,\"cacheCreationEphemeral1hInputTokens\":0}}}";
        List<TokenUsage> used = parser.parse(result).orElseThrow().tokens();
        // modelUsage, not usage: usage covers the main loop only (input 10), modelUsage the whole run (908).
        assertThat(used).containsExactly(new TokenUsage("claude-haiku-4-5", 908, 0, 35266, 0, 163, 149, 0.0458055));
        assertThat(parser.unreportedTokens()).isEmpty(); // the result counted it all

        ClaudeStreamParser stopped = new ClaudeStreamParser();
        String call = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-opus-4-5\",\"usage\":{\"input_tokens\":12,"
                + "\"cache_read_input_tokens\":3000,\"cache_creation_input_tokens\":500,\"cache_creation\":{\"ephemeral_1h_input_tokens\":100},\"output_tokens\":%d}}}";
        stopped.parse(call.formatted(3));
        stopped.parse(call.formatted(40)); // the same call again, further on: the last one stands
        stopped.parse(call.replace("msg_1", "msg_2").formatted(7));
        assertThat(stopped.unreportedTokens()).containsExactly(new TokenUsage("claude-opus-4-5", 24, 6000, 1000, 200, 47, 0, null));
    }

    @Test
    void codexReportsInputWithTheCachedPartInsideSoItIsSplit() {
        CodexStreamParser parser = new CodexStreamParser();
        parser.parse("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":20588,\"cached_input_tokens\":20224,"
                + "\"cache_write_input_tokens\":0,\"output_tokens\":69,\"reasoning_output_tokens\":62}}");
        assertThat(parser.tokens("gpt-5.5")).containsExactly(new TokenUsage("gpt-5.5", 364, 20224, 0, 0, 69, 62, null));
        assertThat(parser.tokens("gpt-5.5").get(0).total()).isEqualTo(20588 + 69);
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
