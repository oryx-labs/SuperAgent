package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.RunCredential;
import com.superagent.runtime.acp.AcpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link GrokAcpAgentRunner} against a fake {@code grok} that speaks the ACP shapes
 * measured from Grok Build CLI 1.0.77 — including {@code session/request_permission} as a
 * JSON-RPC request that must be answered with a response (not a notification).
 */
class GrokAcpAgentRunnerTest {

    @Test
    void mapsHttpMcpServersAndKeepsTheTokenOutOfTheServerRecordShape() {
        String mcp = "{\"mcpServers\":{\"superagent\":{\"type\":\"http\",\"url\":\"http://127.0.0.1:18080/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer turn-token\"}}}}";
        AgentRunRequest req = new AgentRunRequest("s1", null, "/w", "hi", null, List.of(), false, mcp, null);

        List<AcpClient.McpServer> servers = GrokAcpAgentRunner.mcpServers(req);
        assertThat(servers).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("superagent");
            assertThat(s.type()).isEqualTo("http");
            assertThat(s.url()).isEqualTo("http://127.0.0.1:18080/mcp");
            assertThat(s.headers()).containsEntry("Authorization", "Bearer turn-token");
        });
    }

    @Test
    void mapsStdioConnectorEnvFromObjectForm() {
        String mcp = "{\"mcpServers\":{\"corp\":{\"type\":\"stdio\",\"command\":\"/bin/mcp\","
                + "\"args\":[\"--flag\"],\"env\":{\"API_KEY\":\"k\"}}}}";
        AgentRunRequest req = new AgentRunRequest("s1", null, "/w", "hi", null, List.of(), false, mcp, null);

        List<AcpClient.McpServer> servers = GrokAcpAgentRunner.mcpServers(req);
        assertThat(servers).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("stdio");
            assertThat(s.command()).isEqualTo("/bin/mcp");
            assertThat(s.args()).containsExactly("--flag");
            assertThat(s.env()).containsEntry("API_KEY", "k");
        });
    }

    @Test
    void grokHomeLivesUnderTheWorkspaceSoSessionLoadCanFindIt(@TempDir Path dir) {
        AgentRunRequest req = new AgentRunRequest("chat-9", null, dir.toString(), "hi", null, List.of(), false, null, null);
        assertThat(GrokAcpAgentRunner.grokHome(req))
                .isEqualTo(dir.resolve(".superagent/grok-home/chat-9"));
    }

    @Test
    void fsPathsMustStayInsideTheWorkspace(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "ok");
        AgentRunRequest req = new AgentRunRequest("s1", null, dir.toString(), "hi", null, List.of(), false, null, null);
        assertThat(GrokAcpAgentRunner.resolveInWorkspace(req, file.toString(), true))
                .isEqualTo(file.toRealPath());
        assertThat(GrokAcpAgentRunner.resolveInWorkspace(req, dir.resolve("../outside.txt").toString(), false)).isNull();
        assertThat(GrokAcpAgentRunner.resolveInWorkspace(req, "/etc/passwd", false)).isNull();
        Path secret = dir.resolve(".superagent/grok-home/s1/.grok/auth.json");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "secret");
        assertThat(GrokAcpAgentRunner.resolveInWorkspace(req, secret.toString(), true)).isNull();
    }

    @Test
    void runsATurnAnsweringNestedPermissionWithoutSkipPermissions(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("stdin.log");
        Path fake = dir.resolve("grok");
        Files.writeString(fake, fakeAgentScript(log, "happy"));
        fake.toFile().setExecutable(true);

        List<AgentEvent> events = new ArrayList<>();
        // skipPermissions=false: must map run_terminal_command → Bash against the allowlist.
        AgentRunRequest req = new AgentRunRequest("s1", null, dir.toString(), "use echo", "mock-model",
                List.of("Bash", "Read"), false, null, "You are terse.")
                .withCredential(new RunCredential("c1", "https://api.x.ai/v1", "xai-test", false));

        new GrokAcpAgentRunner(fake.toString(), 30).run(req, events::add, new Cancellation());

        assertThat(events).extracting(AgentEvent::type)
                .contains(AgentEventType.SYSTEM, AgentEventType.TEXT, AgentEventType.RESULT)
                .doesNotContain(AgentEventType.ERROR);
        assertThat(events.stream().filter(e -> e.type() == AgentEventType.RESULT).map(AgentEvent::payload))
                .containsExactly("hello done via tool");

        String stdin = Files.readString(log);
        assertThat(stdin).contains("\"method\":\"session/new\"");
        assertThat(stdin).contains("\"rules\":\"You are terse.\"");
        assertThat(stdin).contains("\"id\":9001");
        assertThat(stdin).contains("\"optionId\":\"allow_once\"");
        assertThat(stdin).doesNotContain("session/request_permission/response");
        assertThat(stdin).doesNotContain("--always-approve");
    }

    @Test
    void loadReplayIsNotMixedIntoThisTurnResult(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("stdin.log");
        Path fake = dir.resolve("grok");
        Files.writeString(fake, fakeAgentScript(log, "load"));
        fake.toFile().setExecutable(true);

        List<AgentEvent> events = new ArrayList<>();
        AgentRunRequest req = new AgentRunRequest("s1", "sess-fake-1", dir.toString(), "continue", "mock-model",
                List.of("*"), true, null, null)
                .withCredential(new RunCredential("c1", "https://api.x.ai/v1", "xai-test", false));

        new GrokAcpAgentRunner(fake.toString(), 30).run(req, events::add, new Cancellation());

        assertThat(events.stream().filter(e -> e.type() == AgentEventType.RESULT).map(AgentEvent::payload))
                .containsExactly("NEW ANSWER");
        assertThat(Files.readString(log)).contains("\"method\":\"session/load\"");
    }

    @Test
    void promptJsonRpcErrorEmitsErrorNotResult(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("stdin.log");
        Path fake = dir.resolve("grok");
        Files.writeString(fake, fakeAgentScript(log, "auth-error"));
        fake.toFile().setExecutable(true);

        List<AgentEvent> events = new ArrayList<>();
        AgentRunRequest req = new AgentRunRequest("s1", null, dir.toString(), "hi", "mock-model",
                List.of("*"), true, null, null)
                .withCredential(new RunCredential("c1", "https://api.x.ai/v1", "bad-key", false));

        new GrokAcpAgentRunner(fake.toString(), 30).run(req, events::add, new Cancellation());

        assertThat(events).extracting(AgentEvent::type)
                .contains(AgentEventType.SYSTEM, AgentEventType.ERROR)
                .doesNotContain(AgentEventType.RESULT);
        assertThat(events.stream().filter(e -> e.type() == AgentEventType.ERROR).map(AgentEvent::payload))
                .anyMatch(p -> p.contains("authentication") || p.contains("Unauthorized"));
    }

    /** A stand-in {@code grok}: speaks ACP on stdio and records every stdin line. */
    private static String fakeAgentScript(Path log, String mode) {
        return """
                #!/usr/bin/env node
                const fs = require('fs');
                const readline = require('readline');
                const logPath = %s;
                const mode = %s;
                const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
                let promptId = null;
                function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
                rl.on('line', (line) => {
                  if (!line.trim()) return;
                  fs.appendFileSync(logPath, line + '\\n');
                  let msg; try { msg = JSON.parse(line); } catch { return; }
                  if (msg.id === 9001 && msg.result && !msg.method) {
                    send({ jsonrpc: '2.0', method: 'session/update', params: {
                      update: { sessionUpdate: 'agent_message_chunk',
                        content: { type: 'text', text: 'done via tool' } } } });
                    send({ jsonrpc: '2.0', method: 'session/update', params: {
                      update: { sessionUpdate: 'turn_completed', usage: {
                        inputTokens: 10, outputTokens: 3, totalTokens: 15,
                        cachedReadTokens: 2, cacheCreationTokens: 0, reasoningTokens: 0 } } } });
                    if (promptId != null) {
                      send({ jsonrpc: '2.0', id: promptId, result: {
                        stopReason: 'end_turn',
                        _meta: { usage: { inputTokens: 10, outputTokens: 3, totalTokens: 15,
                          cachedReadTokens: 2, cacheCreationTokens: 0, reasoningTokens: 0 } } } });
                    }
                    return;
                  }
                  if (msg.method === 'initialize') {
                    send({ jsonrpc: '2.0', id: msg.id, result: {
                      protocolVersion: 1,
                      agentCapabilities: { loadSession: true, mcpCapabilities: { http: true } } } });
                  } else if (msg.method === 'session/new') {
                    send({ jsonrpc: '2.0', id: msg.id, result: { sessionId: 'sess-fake-1' } });
                  } else if (msg.method === 'session/load') {
                    // Historical replay arrives as session/update before / while the load result does.
                    send({ jsonrpc: '2.0', method: 'session/update', params: {
                      update: { sessionUpdate: 'agent_message_chunk',
                        content: { type: 'text', text: 'OLD ANSWER' } } } });
                    send({ jsonrpc: '2.0', id: msg.id, result: { sessionId: 'sess-fake-1' } });
                    send({ jsonrpc: '2.0', method: 'session/update', params: {
                      update: { sessionUpdate: 'agent_message_chunk',
                        content: { type: 'text', text: 'OLD ANSWER' } } } });
                  } else if (msg.method === 'session/prompt') {
                    promptId = msg.id;
                    if (mode === 'auth-error') {
                      send({ jsonrpc: '2.0', id: promptId, error: {
                        code: -32000, message: 'Unauthorized: authentication failed' } });
                      return;
                    }
                    if (mode === 'load') {
                      send({ jsonrpc: '2.0', method: 'session/update', params: {
                        update: { sessionUpdate: 'agent_message_chunk',
                          content: { type: 'text', text: 'NEW ANSWER' } } } });
                      send({ jsonrpc: '2.0', id: promptId, result: { stopReason: 'end_turn' } });
                      return;
                    }
                    send({ jsonrpc: '2.0', method: 'session/update', params: {
                      update: { sessionUpdate: 'agent_message_chunk',
                        content: { type: 'text', text: 'hello ' } } } });
                    // ACP nests title / toolCallId under params.toolCall.
                    send({ jsonrpc: '2.0', id: 9001, method: 'session/request_permission', params: {
                      sessionId: 'sess-fake-1',
                      toolCall: {
                        toolCallId: 'tc1',
                        title: 'Run a shell command',
                        name: 'run_terminal_command',
                        kind: 'execute'
                      },
                      options: [
                        { optionId: 'allow_once', name: 'Allow once', kind: 'allow_once' },
                        { optionId: 'reject_once', name: 'Reject', kind: 'reject_once' }
                      ] } });
                  }
                });
                """.formatted(toJsString(log.toString()), toJsString(mode));
    }

    private static String toJsString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
