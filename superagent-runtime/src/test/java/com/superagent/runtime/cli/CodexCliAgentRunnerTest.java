package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ImageAttachment;
import com.superagent.core.chat.RunCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the Codex wire mapping against lines captured from {@code codex exec --json}. */
class CodexCliAgentRunnerTest {

    private static List<AgentEventType> types(List<CodexStreamParser.ParsedEvent> events) {
        return events.stream().map(CodexStreamParser.ParsedEvent::type).toList();
    }

    @Test
    void parsesATurnIntoThreadIdTextAndResult() {
        CodexStreamParser p = new CodexStreamParser();
        assertThat(p.parse("{\"type\":\"thread.started\",\"thread_id\":\"01a1-t\"}"))
                .containsExactly(new CodexStreamParser.ParsedEvent(AgentEventType.SYSTEM, "01a1-t"));
        // configuration warnings arrive as error items; they are not failures
        assertThat(p.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"i0\",\"type\":\"error\",\"message\":\"ignoring 1 setting\"}}")).isEmpty();
        assertThat(types(p.parse("{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"command_execution\",\"command\":\"ls\"}}")))
                .containsExactly(AgentEventType.TOOL_USE);
        assertThat(p.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\",\"type\":\"command_execution\",\"command\":\"ls\"}}"))
                .isEmpty(); // already announced on start
        assertThat(p.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"i2\",\"type\":\"agent_message\",\"text\":\"蓝鲸42\"}}"))
                .containsExactly(new CodexStreamParser.ParsedEvent(AgentEventType.TEXT, "蓝鲸42"));
        assertThat(p.parse("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":20187,\"output_tokens\":5}}"))
                .containsExactly(new CodexStreamParser.ParsedEvent(AgentEventType.RESULT, "蓝鲸42"));
        assertThat(p.threadId()).isEqualTo("01a1-t");
        assertThat(p.finished()).isTrue();

        assertThat(types(new CodexStreamParser().parse("{\"type\":\"turn.failed\",\"error\":{\"message\":\"quota\"}}")))
                .containsExactly(AgentEventType.ERROR);
    }

    @Test
    void passesHttpMcpServersAsConfigOverridesWithTheTokenKeptOutOfArgv() {
        CodexCliAgentRunner runner = new CodexCliAgentRunner("codex", 600);
        String mcp = "{\"mcpServers\":{\"superagent\":{\"type\":\"http\",\"url\":\"http://127.0.0.1:8080/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer s3cret\"}}}}";
        AgentRunRequest req = new AgentRunRequest("s1", null, "/w", "hi", null, List.of(), false, mcp, null);

        List<String> cmd = runner.buildCommand(req, null);
        assertThat(cmd).containsSubsequence("-c", "mcp_servers.superagent.url=\"http://127.0.0.1:8080/mcp\"",
                "-c", "mcp_servers.superagent.default_tools_approval_mode=\"approve\"",
                "-c", "mcp_servers.superagent.bearer_token_env_var=\"SUPERAGENT_MCP_TOKEN_SUPERAGENT\"");
        assertThat(String.join(" ", cmd)).doesNotContain("s3cret");
        assertThat(CodexCliAgentRunner.mcpServers(req)).singleElement()
                .satisfies(s -> assertThat(s.token()).isEqualTo("s3cret"));
    }

    @Test
    void aPoolCredentialPointsTheRunAtItsEndpointWithTheKeyOutOfArgv() {
        CodexCliAgentRunner runner = new CodexCliAgentRunner("codex", 600);
        AgentRunRequest req = new AgentRunRequest("s1", null, "/w", "hi", null, List.of(), false, null, null)
                .withCredential(new RunCredential("c1", "https://relay.example/v1", "sk-relay-key", false));
        List<String> cmd = runner.buildCommand(req, null);
        assertThat(cmd).containsSubsequence("-c", "model_provider=\"openai\"", "-c", "openai_base_url=\"https://relay.example/v1\"");
        assertThat(String.join(" ", cmd)).doesNotContain("sk-relay-key");
    }

    @Test
    void buildsNewAndResumeCommandsWithModelAndSandbox() {
        CodexCliAgentRunner runner = new CodexCliAgentRunner("codex", 600);
        AgentRunRequest first = new AgentRunRequest("s1", null, "/w", "-rm everything", "gpt-6-sol",
                List.of("Read", "Edit"), false, null, null);
        assertThat(runner.buildCommand(first, null)).containsExactly(
                "codex", "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write", "-m", "gpt-6-sol", "-");

        AgentRunRequest resume = new AgentRunRequest("s1", "s1", "/w", "again", null, List.of(), false, null, null);
        assertThat(runner.buildCommand(resume, "01a1-t")).containsExactly(
                "codex", "exec", "--json", "--skip-git-repo-check", "-s", "read-only", "resume", "01a1-t", "-");

        // One "--image=" per image: a bare "-i" takes several values and would swallow the "-" stdin marker.
        AgentRunRequest withImage = new AgentRunRequest("s1", "s1", "/w", "look", null, List.of(), false, null, null,
                List.of(new ImageAttachment("/data/a/0.png", "image/png")));
        assertThat(runner.buildCommand(withImage, "01a1-t")).containsExactly(
                "codex", "exec", "--json", "--skip-git-repo-check", "-s", "read-only", "resume",
                "--image=/data/a/0.png", "01a1-t", "-");
    }

    @Test
    void aTamperedThreadIdFileNeverReachesArgv(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".superagent/codex-sessions/s1");
        Files.createDirectories(f.getParent());
        // What a prompt-injected agent with workspace-write could plant to disable the sandbox on resume.
        Files.writeString(f, "--dangerously-bypass-approvals-and-sandbox");
        Path log = dir.resolve("argv.log");
        Path fake = dir.resolve("codex");
        Files.writeString(fake, "#!/bin/sh\necho \"$*\" > '" + log + "'\ncat > /dev/null\n");
        fake.toFile().setExecutable(true);

        new CodexCliAgentRunner(fake.toString(), 30).run(
                new AgentRunRequest("s1", "s1", dir.toString(), "go on", null, List.of(), false, null, null),
                e -> { }, new Cancellation());

        String argv = Files.readString(log);
        assertThat(argv).doesNotContain("dangerously").doesNotContain("resume").contains("-s read-only");
    }
}
