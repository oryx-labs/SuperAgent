package com.superagent.worker.agent;

import com.superagent.core.chat.AgentEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the parser against real lines from {@code claude --output-format stream-json}. */
class ClaudeStreamParserTest {

    private final ClaudeStreamParser parser = new ClaudeStreamParser();

    private AgentEventType type(String line) {
        return parser.parse(line).map(ClaudeStreamParser.ParsedEvent::type).orElse(null);
    }

    @Test
    void mapsTheEventKindsSuperAgentSurfaces() {
        assertThat(parser.parse("{\"type\":\"system\",\"subtype\":\"init\","
                + "\"session_id\":\"s1\",\"model\":\"claude-opus-4-8\"}"))
                .get().extracting(ClaudeStreamParser.ParsedEvent::type, ClaudeStreamParser.ParsedEvent::payload)
                .containsExactly(AgentEventType.SYSTEM, "s1");

        assertThat(parser.parse("{\"type\":\"stream_event\",\"event\":{\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"ello\"},\"type\":\"content_block_delta\"}}"))
                .get().extracting(ClaudeStreamParser.ParsedEvent::type, ClaudeStreamParser.ParsedEvent::payload)
                .containsExactly(AgentEventType.TEXT, "ello");

        assertThat(type("{\"type\":\"stream_event\",\"event\":{\"delta\":{\"type\":\"thinking_delta\","
                + "\"thinking\":\"hmm\"},\"type\":\"content_block_delta\"}}")).isEqualTo(AgentEventType.THINKING);
        assertThat(type("{\"type\":\"stream_event\",\"event\":{\"content_block\":{\"type\":\"tool_use\","
                + "\"name\":\"Bash\"},\"type\":\"content_block_start\"}}")).isEqualTo(AgentEventType.TOOL_USE);

        assertThat(parser.parse("{\"is_error\":false,\"result\":\"done\",\"type\":\"result\"}"))
                .get().extracting(ClaudeStreamParser.ParsedEvent::type, ClaudeStreamParser.ParsedEvent::payload)
                .containsExactly(AgentEventType.RESULT, "done");
        assertThat(type("{\"is_error\":true,\"result\":\"boom\",\"type\":\"result\"}")).isEqualTo(AgentEventType.ERROR);
    }

    @Test
    void surfacesToolResultText() {
        // Array-of-text-blocks form (what the create_task MCP tool returns).
        assertThat(parser.parse("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"已创建任务 abc（标题）\"}]}]}}"))
                .get().extracting(ClaudeStreamParser.ParsedEvent::type, ClaudeStreamParser.ParsedEvent::payload)
                .containsExactly(AgentEventType.TOOL_RESULT, "已创建任务 abc（标题）");

        // Plain-string content form.
        assertThat(parser.parse("{\"type\":\"user\",\"message\":{\"content\":"
                + "[{\"type\":\"tool_result\",\"content\":\"ok\"}]}}"))
                .get().extracting(ClaudeStreamParser.ParsedEvent::type, ClaudeStreamParser.ParsedEvent::payload)
                .containsExactly(AgentEventType.TOOL_RESULT, "ok");
    }

    @Test
    void ignoresNoiseAndBookkeepingFrames() {
        assertThat(type("{\"type\":\"system\",\"subtype\":\"hook_started\"}")).isNull();
        assertThat(type("{\"type\":\"user\",\"message\":{}}")).isNull();
        assertThat(type("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_stop\"}}")).isNull();
        assertThat(type("{\"type\":\"stream_event\",\"event\":{\"content_block\":{\"type\":\"text\"},"
                + "\"type\":\"content_block_start\"}}")).isNull();
        assertThat(type("not json")).isNull();
    }

    @Test
    void errorResultsCarryTheirReasonAndDenialsAreReported() {
        ClaudeStreamParser.ParsedEvent missing = parser.parse("{\"type\":\"result\",\"subtype\":\"error_during_execution\","
                + "\"is_error\":true,\"errors\":[\"No conversation found with session ID: s1\"]}").orElseThrow();
        assertThat(missing.type()).isEqualTo(AgentEventType.ERROR);
        assertThat(missing.payload()).isEqualTo("No conversation found with session ID: s1");

        ClaudeStreamParser.ParsedEvent denied = parser.parse("{\"type\":\"result\",\"is_error\":false,\"result\":\"ok\","
                + "\"permission_denials\":[{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"rm -rf build\"}}]}").orElseThrow();
        assertThat(denied.permissionDenials()).containsExactly("Bash: rm -rf build");
    }
}
