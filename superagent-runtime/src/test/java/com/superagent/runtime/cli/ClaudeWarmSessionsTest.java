package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.tenant.TenantContext;
import com.superagent.runtime.mcp.McpGrants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A conversation's CLI stays running between turns, through a fake {@code claude} that answers each stdin line. */
class ClaudeWarmSessionsTest {

    @TempDir
    Path dir;

    private ClaudeCliAgentRunner runner;

    @AfterEach
    void stop() {
        if (runner != null) {
            runner.stopWarm();
        }
        TenantContext.clear();
    }

    /** Logs each start with its argv; answers every message with its pid, and fails a resume of "gone". */
    private Path fakeClaude() throws Exception {
        Path fake = dir.resolve("claude");
        String answer = "  echo '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":"
                + "{\"type\":\"text_delta\",\"text\":\"pid '$$'\"}}}'; echo '{\"type\":\"result\",\"is_error\":false,\"result\":\"pid '$$'\"}'";
        Files.writeString(fake, String.join("\n",
                "#!/bin/sh",
                "echo \"start $$ $*\" >> '" + dir.resolve("starts.log") + "'",
                "case \"$*\" in",
                "  *'--resume gone'*) echo '{\"type\":\"result\",\"is_error\":true,"
                        + "\"errors\":[\"No conversation found with session ID: gone\"]}'; exit 1 ;;",
                "esac",
                "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\"}'",
                "case \"$*\" in",
                "  *'--input-format stream-json'*) n=0; while read line; do",
                "    n=$((n+1))",
                // Like the real CLI in this mode: each result carries the process's running totals.
                "    echo '{\"type\":\"result\",\"is_error\":false,\"result\":\"pid '$$'\",\"total_cost_usd\":'$n',"
                        + "\"modelUsage\":{\"sonnet\":{\"inputTokens\":'$((n*100))',\"outputTokens\":'$((n*10))'}}}'",
                "  done ;;",
                "  *)",
                answer,
                "  ;;",
                "esac", ""));
        fake.toFile().setExecutable(true);
        return fake;
    }

    private AgentRunRequest turn(String agentSessionId, String systemPrompt, String mcpConfig) {
        return new AgentRunRequest("s1", agentSessionId, dir.toString(), "hello", "sonnet", List.of(), false, mcpConfig,
                systemPrompt);
    }

    @Test
    void aSessionsTurnsShareOneCliAndItsTokenOnlyActsDuringATurn() throws Exception {
        TenantContext.set("acme");
        McpGrants grants = new McpGrants("http://127.0.0.1:1/mcp");
        runner = new ClaudeCliAgentRunner(fakeClaude().toString(), 30);
        ClaudeWarmSessions warm = runner.enableWarm(grants, Duration.ofMinutes(10), 5);

        McpGrants.Grant first = grants.openChat("ada", "a1", "s1", "m1", dir.toString());
        List<String> replies = new ArrayList<>();
        List<com.superagent.core.usage.TokenUsage> billed = new ArrayList<>();
        List<String> costs = new ArrayList<>();
        java.util.function.Consumer<AgentEvent> usage = e -> {
            if (e.type() == AgentEventType.USAGE) {
                costs.add(e.payload());
            } else if (e.type() == AgentEventType.TOKENS) {
                billed.addAll(Tokens.parse(e.payload()));
            }
        };
        runner.runChat(turn(null, "persona", grants.mcpConfig(first)), e -> {
            collect(e, replies);
            usage.accept(e);
        }, new Cancellation());
        grants.close(first);

        // The next turn has its own grant; the running CLI still sends the first token, which acts for it meanwhile.
        McpGrants.Grant second = grants.openChat("bob", "a1", "s1", "m2", dir.toString());
        List<Optional<McpGrants.Grant>> seenDuringTurn = new ArrayList<>();
        runner.runChat(turn("s1", "persona", grants.mcpConfig(second)), e -> {
            collect(e, replies);
            usage.accept(e);
            if (e.type() == AgentEventType.RESULT) {
                seenDuringTurn.add(grants.find(first.token()));
            }
        }, new Cancellation());
        grants.close(second);

        assertThat(replies).hasSize(2).allMatch(r -> r.equals(replies.get(0)));      // the same process answered both
        assertThat(seenDuringTurn).singleElement().satisfies(g -> assertThat(g).contains(second));
        assertThat(grants.find(first.token())).isEmpty();                                 // idle: it acts for no one
        assertThat(warm.idleCount()).isEqualTo(1);
        assertThat(billed).extracting(t -> t.input() + "/" + t.output()).containsExactly("100/10", "100/10"); // not 200/20
        assertThat(costs).containsExactly("1.0", "1.0");

        // A changed system prompt (e.g. a skill was attached) needs a CLI started with it.
        runner.runChat(turn("s1", "persona + skill", null), e -> collect(e, replies), new Cancellation());
        assertThat(replies.get(2)).isNotEqualTo(replies.get(0));
        assertThat(Files.readAllLines(dir.resolve("starts.log"))).hasSize(2)
                .allMatch(l -> l.contains("--input-format stream-json"));
    }

    @Test
    void aConversationThatCannotBeResumedFallsBackToAnOrdinaryRun() throws Exception {
        runner = new ClaudeCliAgentRunner(fakeClaude().toString(), 30);
        runner.enableWarm(null, Duration.ofMinutes(10), 5);

        List<AgentEvent> events = new ArrayList<>();
        runner.runChat(turn("gone", null, null), events::add, new Cancellation());

        // The ordinary run retries it as a new conversation, as it always has.
        List<String> starts = Files.readAllLines(dir.resolve("starts.log"));
        assertThat(starts).anyMatch(l -> l.contains("--session-id s1"));
        assertThat(events).extracting(AgentEvent::type).contains(AgentEventType.RESULT).doesNotContain(AgentEventType.ERROR);
    }

    private static void collect(AgentEvent e, List<String> replies) {
        if (e.type() == AgentEventType.RESULT) {
            replies.add(e.payload());
        }
    }
}
