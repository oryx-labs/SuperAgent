package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.RunCredential;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The DeepSeek / Kimi / Qwen runners against output captured from the real CLIs (dsh 0.x, kimi 2.5.2). */
class VendorCliRunnersTest {

    private static final String MCP = "{\"mcpServers\":{\"superagent\":{\"type\":\"http\",\"url\":\"http://127.0.0.1:18080/mcp\","
            + "\"headers\":{\"Authorization\":\"Bearer turn-token\"}}}}";

    @Test
    void deepSeekStreamAndProfileOverlay() throws Exception {
        List<String> stream = List.of(
                "{\"type\":\"session\",\"sessionId\":\"session-1e28\",\"cwd\":\"/w\"}",
                "{\"type\":\"status\",\"phase\":\"turn_start\",\"turn\":1}",
                "{\"type\":\"tool_call\",\"callId\":\"tu1\",\"tool\":\"read\",\"input\":{\"path\":\"a.txt\"}}",
                "{\"type\":\"tool_result\",\"callId\":\"tu1\",\"status\":\"ok\",\"result\":\"hello\"}",
                "{\"type\":\"text\",\"text\":\"你好\"}",
                "{\"type\":\"final\",\"sessionId\":\"session-1e28\",\"reason\":{\"kind\":\"completed\"},\"text\":\"你好\"}");
        List<JsonLineCliRunner.Line> lines = new ArrayList<>();
        stream.forEach(l -> lines.addAll(DeepSeekCliAgentRunner.parseLine(l)));
        assertThat(lines).extracting(JsonLineCliRunner.Line::type).containsExactly(AgentEventType.SYSTEM,
                AgentEventType.TOOL_USE, AgentEventType.TOOL_RESULT, AgentEventType.TEXT, AgentEventType.RESULT);
        assertThat(lines).extracting(JsonLineCliRunner.Line::payload)
                .containsExactly("session-1e28", "read", "hello", "你好", "你好");
        assertThat(DeepSeekCliAgentRunner.parseLine("{\"type\":\"final\",\"reason\":{\"kind\":\"failed\",\"error\":{\"message\":\"quota\"}}}"))
                .extracting(JsonLineCliRunner.Line::type).containsExactly(AgentEventType.ERROR);

        // The model edits an existing entry; the MCP client is a new one, so it goes under insert. The token
        // stays out of the file: dsh reads it from the run's environment.
        String patch = DeepSeekCliAgentRunner.patch(request("deepseek-pro"));
        assertThat(patch).startsWith("- id: agent-default-model").contains("model: \"deepseek-pro\"")
                .contains("- insert:\n    - id: mcp-superagent").contains("transport: streamable-http")
                .contains("process.env.SUPERAGENT_MCP_TOKEN").doesNotContain("turn-token");
    }

    @Test
    void kimiStreamAndConfig() throws Exception {
        KimiCliAgentRunner.Parser parser = new KimiCliAgentRunner.Parser();
        List<JsonLineCliRunner.Line> lines = new ArrayList<>();
        for (String l : List.of(
                "{\"role\":\"meta\",\"type\":\"system.version\",\"version\":\"2.5.2\"}",
                "{\"role\":\"assistant\",\"tool_calls\":[{\"type\":\"function\",\"id\":\"call1\",\"function\":{\"name\":\"Read\",\"arguments\":\"{}\"}}]}",
                "{\"role\":\"tool\",\"tool_call_id\":\"call1\",\"content\":\"1\\thello\"}",
                "{\"role\":\"assistant\",\"content\":\"你好\"}",
                "{\"role\":\"meta\",\"type\":\"session.resume_hint\",\"session_id\":\"session_e6d6\"}")) {
            lines.addAll(parser.parse(l));
        }
        lines.addAll(parser.finish());
        assertThat(lines).extracting(JsonLineCliRunner.Line::type).containsExactly(AgentEventType.TOOL_USE,
                AgentEventType.TOOL_RESULT, AgentEventType.TEXT, AgentEventType.SYSTEM, AgentEventType.RESULT);
        assertThat(lines.get(4).payload()).isEqualTo("你好");

        assertThat(KimiCliAgentRunner.config("kimi-k2", "https://api.moonshot.cn/v1", "sk-test"))
                .contains("default_model = \"superagent/kimi-k2\"").contains("base_url = \"https://api.moonshot.cn/v1\"")
                .contains("api_key = \"sk-test\"");
    }

    @Test
    void qwenMcpConfigUsesStreamableHttp() {
        assertThat(QwenCliAgentRunner.mcpConfig(CodexCliAgentRunner.mcpServers(request("qwen3-coder-plus"))))
                .contains("\"httpUrl\":\"http://127.0.0.1:18080/mcp\"").contains("\"Authorization\":\"Bearer turn-token\"");
    }

    private static AgentRunRequest request(String model) {
        return new AgentRunRequest("s1", null, "/tmp", "hi", model, List.of(), false, MCP, null, List.of(), null,
                new RunCredential("c1", "http://127.0.0.1:1", "sk-test", false));
    }
}
