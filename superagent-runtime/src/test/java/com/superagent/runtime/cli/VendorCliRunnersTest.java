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

    @Test
    void codeBuddyKeepsClaudeShapedMcpAndInjectsRegionEnv() throws Exception {
        AgentRunRequest req = request("auto");
        assertThat(CodeBuddyCliAgentRunner.mcpConfig(req)).isEqualTo(MCP);

        List<java.nio.file.Path> cleanup = new java.util.ArrayList<>();
        List<String> cmd = new CodeBuddyCliAgentRunner("codebuddy", 60).buildCommand(req, null, cleanup);
        assertThat(cmd).contains("-p", "--output-format", "stream-json", "--include-partial-messages",
                "--model", "auto", "--strict-mcp-config", "--mcp-config");
        assertThat(cmd).containsSequence("--tools", "")
                .containsSequence("--permission-mode", "dontAsk")
                .containsSequence("--setting-sources", "");
        assertThat(cmd).doesNotContain("-y", "--dangerously-skip-permissions", "--resume");
        String mcpFile = cmd.get(cmd.indexOf("--mcp-config") + 1);
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(mcpFile)))
                .contains("\"type\":\"http\"").contains("Bearer turn-token");
        for (java.nio.file.Path f : cleanup) {
            java.nio.file.Files.deleteIfExists(f);
        }

        assertThat(CodeBuddyCliAgentRunner.environment(req.credential(), "internal"))
                .containsEntry("CODEBUDDY_API_KEY", "sk-test")
                .containsEntry("CODEBUDDY_INTERNET_ENVIRONMENT", "internal")
                .containsEntry("CODEBUDDY_BASE_URL", "http://127.0.0.1:1");
        assertThat(CodeBuddyCliAgentRunner.environment(req.credential(), "public"))
                .doesNotContainKey("CODEBUDDY_INTERNET_ENVIRONMENT");
        assertThat(CodeBuddyCliAgentRunner.normalizeInternetEnvironment(null)).isEqualTo("internal");
        assertThat(CodeBuddyCliAgentRunner.normalizeInternetEnvironment("IOA")).isEqualTo("ioa");
    }

    @Test
    void codeBuddyResumeAndAllowedTools() throws Exception {
        AgentRunRequest req = new AgentRunRequest("s1", "cli-sess", "/tmp", "hi", "auto",
                List.of("Read", "Edit"), false, null, "be brief", List.of(), null,
                new RunCredential("c1", null, "sk-test", false));
        List<String> cmd = new CodeBuddyCliAgentRunner("codebuddy", 60).buildCommand(req, "cli-sess", List.of());
        assertThat(cmd).containsSequence("--resume", "cli-sess");
        assertThat(cmd).containsSequence("--allowedTools", "Read Edit")
                .containsSequence("--tools", "Read,Edit")
                .containsSequence("--setting-sources", "");
        assertThat(cmd).doesNotContain("-y", "--append-system-prompt", "--mcp-config");
    }

    @Test
    void geminiStreamMcpAndApproval() throws Exception {
        GeminiStreamParser parser = new GeminiStreamParser();
        List<JsonLineCliRunner.Line> lines = new ArrayList<>();
        for (String l : List.of(
                "{\"type\":\"init\",\"session_id\":\"g-sess-1\",\"model\":\"gemini-2.5-pro\"}",
                "{\"type\":\"message\",\"role\":\"assistant\",\"delta\":true,\"content\":\"Hel\"}",
                "{\"type\":\"message\",\"role\":\"assistant\",\"delta\":true,\"content\":\"lo\"}",
                "{\"type\":\"tool_use\",\"tool_name\":\"read_file\",\"tool_id\":\"t1\"}",
                "{\"type\":\"tool_result\",\"tool_id\":\"t1\",\"output\":\"ok\"}",
                "{\"type\":\"result\",\"status\":\"success\",\"stats\":{\"input_tokens\":10,\"output_tokens\":2,\"cached\":0}}")) {
            lines.addAll(parser.parse(l));
        }
        assertThat(lines).extracting(JsonLineCliRunner.Line::type).containsExactly(
                AgentEventType.SYSTEM, AgentEventType.TEXT, AgentEventType.TEXT,
                AgentEventType.TOOL_USE, AgentEventType.TOOL_RESULT, AgentEventType.RESULT);
        assertThat(lines.get(0).payload()).isEqualTo("g-sess-1");
        assertThat(lines.get(1).payload()).isEqualTo("Hel");
        assertThat(lines.get(2).payload()).isEqualTo("lo");
        assertThat(lines.get(5).payload()).isEqualTo("Hello");
        assertThat(parser.tokens("gemini-2.5-pro")).singleElement()
                .satisfies(u -> {
                    assertThat(u.input()).isEqualTo(10);
                    assertThat(u.output()).isEqualTo(2);
                });

        AgentRunRequest req = request("gemini-2.5-pro");
        assertThat(GeminiCliAgentRunner.settingsJson(CodexCliAgentRunner.mcpServers(req)))
                .contains("\"httpUrl\":\"http://127.0.0.1:18080/mcp\"").contains("Bearer turn-token");
        assertThat(GeminiCliAgentRunner.approvalMode(req)).isEqualTo("plan");
        assertThat(GeminiCliAgentRunner.approvalMode(new AgentRunRequest("s1", null, "/tmp", "hi", "m",
                List.of("Edit"), false, null, null, List.of(), null,
                new RunCredential("c1", null, "sk", false)))).isEqualTo("auto_edit");
        assertThat(GeminiCliAgentRunner.environment(req.credential(), java.nio.file.Path.of("/tmp/g")))
                .containsEntry("GEMINI_API_KEY", "sk-test")
                .containsEntry("GEMINI_CLI_HOME", "/tmp/g")
                .containsEntry("HOME", "/tmp/g")
                .doesNotContainKey("GEMINI_CONFIG_DIR");

        List<String> cmd = new GeminiCliAgentRunner("gemini", 60).buildCommand(req, null, List.of());
        assertThat(cmd).contains("-p", "--output-format", "stream-json", "--skip-trust",
                "--approval-mode", "plan", "--model", "gemini-2.5-pro");
        assertThat(java.nio.file.Files.readString(
                java.nio.file.Path.of("/tmp/.superagent/gemini-home/s1/.gemini/settings.json")))
                .contains("httpUrl");
    }

    @Test
    void minimaxStreamMcpAndPermission() throws Exception {
        McodeStreamParser parser = new McodeStreamParser();
        List<JsonLineCliRunner.Line> lines = new ArrayList<>();
        for (String l : List.of(
                "{\"type\":\"session.started\",\"sessionId\":\"mvs_abc\"}",
                "{\"type\":\"item.started\",\"item\":{\"id\":\"t1\",\"type\":\"tool_call\",\"toolCall\":{\"name\":\"Bash\"}}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"t1\",\"type\":\"tool_call\",\"toolCall\":{\"name\":\"Bash\",\"result\":\"done\"}}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"m1\",\"type\":\"agent_message\",\"text\":\"你好\"}}",
                "{\"type\":\"exec.completed\",\"result\":{\"status\":\"succeeded\",\"output\":\"你好\",\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}")) {
            lines.addAll(parser.parse(l));
        }
        assertThat(lines).extracting(JsonLineCliRunner.Line::type).containsExactly(
                AgentEventType.SYSTEM, AgentEventType.TOOL_USE, AgentEventType.TOOL_RESULT,
                AgentEventType.TEXT, AgentEventType.RESULT);
        assertThat(lines.get(0).payload()).isEqualTo("mvs_abc");
        assertThat(parser.tokens("MiniMax-M2.1")).singleElement()
                .satisfies(u -> assertThat(u.input() + u.output()).isEqualTo(7));

        AgentRunRequest req = request("MiniMax-M2.1");
        assertThat(MinimaxCliAgentRunner.mcpJson(CodexCliAgentRunner.mcpServers(req)))
                .contains("\"type\":\"http\"").contains("Bearer turn-token");
        assertThat(MinimaxCliAgentRunner.modelArg("MiniMax-M2.1")).isEqualTo("minimax/MiniMax-M2.1");
        assertThat(MinimaxCliAgentRunner.modelArg("minimax/MiniMax-M2.1")).isEqualTo("minimax/MiniMax-M2.1");
        assertThat(MinimaxCliAgentRunner.permission(req)).isEqualTo("off");
        assertThat(MinimaxCliAgentRunner.configYaml("sk-test", "minimax/MiniMax-M2.1"))
                .contains("apiKey: \"sk-test\"").contains("defaultModel: \"minimax/MiniMax-M2.1\"");

        List<String> cmd = new MinimaxCliAgentRunner("mcode", 60).buildCommand(req, null, List.of());
        assertThat(cmd).containsSequence("exec", "--output-format", "stream-json")
                .containsSequence("--permission", "off")
                .containsSequence("--model", "minimax/MiniMax-M2.1")
                .contains("hi");
        assertThat(java.nio.file.Files.readString(
                java.nio.file.Path.of("/tmp/.superagent/mcode-home/s1/mcp.json")))
                .contains("http://127.0.0.1:18080/mcp");
    }

    @Test
    void qoderUsesClaudeShapedFlagsAndKeepsTheTokenInTheEnvironment() throws Exception {
        AgentRunRequest req = request("qwen3-coder");
        List<java.nio.file.Path> cleanup = new java.util.ArrayList<>();
        List<String> cmd = new QoderCliAgentRunner("qodercn", 60).buildCommand(req, null, cleanup);
        assertThat(cmd).contains("-p", "--output-format", "stream-json", "--include-partial-messages",
                "--model", "qwen3-coder", "--strict-mcp-config", "--mcp-config");
        assertThat(cmd).contains("hi");   // the prompt travels in argv
        String mcpFile = cmd.get(cmd.indexOf("--mcp-config") + 1);
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(mcpFile)))
                .contains("\"type\":\"http\"").contains("Bearer turn-token");
        for (java.nio.file.Path f : cleanup) {
            java.nio.file.Files.deleteIfExists(f);
        }

        // The CN site and the personal access token travel in the environment, never in argv.
        java.util.Map<String, String> env = new QoderCliAgentRunner("qodercn", 60).environment(req);
        assertThat(env).containsEntry(QoderCliAgentRunner.PAT_ENV, "sk-test")
                .containsEntry("NO_BROWSER", "1");
        assertThat(String.join(" ", cmd)).doesNotContain("sk-test");

        // A resumed turn keeps the CLI's own session; a tool allowlist is the CLI's --allowed-tools (comma-separated).
        AgentRunRequest next = new AgentRunRequest("s1", "cli-sess", "/tmp", "hi", "m",
                List.of("Read", "Edit"), false, null, "be brief", List.of(), null,
                new RunCredential("c1", null, "sk-test", false));
        List<String> resumed = new QoderCliAgentRunner("qodercn", 60).buildCommand(next, "cli-sess", List.of());
        assertThat(resumed).containsSequence("--resume", "cli-sess").containsSequence("--allowed-tools", "Read,Edit");
        assertThat(resumed).doesNotContain("--yolo", "--mcp-config");
    }

    private static AgentRunRequest request(String model) {
        return new AgentRunRequest("s1", null, "/tmp", "hi", model, List.of(), false, MCP, null, List.of(), null,
                new RunCredential("c1", "http://127.0.0.1:1", "sk-test", false));
    }
}
