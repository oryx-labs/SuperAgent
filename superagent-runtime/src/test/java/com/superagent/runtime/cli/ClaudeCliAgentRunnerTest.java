package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ImageAttachment;
import com.superagent.core.chat.RunCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the persona is injected as {@code --append-system-prompt} before the {@code --} separator. */
class ClaudeCliAgentRunnerTest {

    private final ClaudeCliAgentRunner runner = new ClaudeCliAgentRunner("claude", 600);

    private static AgentRunRequest request(String systemPrompt) {
        return new AgentRunRequest("s1", null, "/tmp/ws", "do the thing", "sonnet",
                List.of(), false, null, systemPrompt);
    }

    @Test
    void injectsPersonaAsAppendSystemPrompt() {
        List<String> cmd = runner.buildCommand(request("You are a meticulous release manager."), null);

        int flag = cmd.indexOf("--append-system-prompt");
        assertThat(flag).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(flag + 1)).isEqualTo("You are a meticulous release manager.");
        // Persona must sit before the end-of-options separator, never as a positional prompt arg.
        assertThat(flag).isLessThan(cmd.indexOf("--"));
    }

    /** With images the message goes on stdin as content blocks; nothing of the prompt stays in argv. */
    @Test
    void imagesMoveTheMessageToStdinAsContentBlocks(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("0.png");
        Files.write(png, new byte[]{1, 2, 3});
        AgentRunRequest req = new AgentRunRequest("s1", null, "/tmp/ws", "what is this?", null,
                List.of(), false, null, null, List.of(new ImageAttachment(png.toString(), "image/png")));

        List<String> cmd = runner.buildCommand(req, null);
        assertThat(cmd).containsSubsequence("--input-format", "stream-json")
                .doesNotContain("--", "what is this?");

        JsonNode content = new ObjectMapper().readTree(runner.stdinMessage(req)).path("message").path("content");
        assertThat(content.get(0).path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(content.get(0).path("source").path("data").asText()).isEqualTo("AQID"); // base64 of {1,2,3}
        assertThat(content.get(1).path("text").asText()).isEqualTo("what is this?");
    }

    /** Memory rides along only when a conversation starts; a resumed one already has its history. */
    @Test
    void memoryJoinsTheSystemPromptOnlyForANewConversation() {
        AgentRunRequest fresh = new AgentRunRequest("s1", null, "/tmp/ws", "hi", null, List.of(), false, null,
                "You are Ada.", List.of(), "- Prefers short answers");
        String sys = runner.buildCommand(fresh, null).get(runner.buildCommand(fresh, null).indexOf("--append-system-prompt") + 1);
        assertThat(sys).startsWith("You are Ada.").contains("- Prefers short answers");

        AgentRunRequest resumed = new AgentRunRequest("s1", "s1", "/tmp/ws", "hi", null, List.of(), false, null,
                "You are Ada.", List.of(), "- Prefers short answers");
        List<String> cmd = runner.buildCommand(resumed, null);
        assertThat(cmd.get(cmd.indexOf("--append-system-prompt") + 1)).isEqualTo("You are Ada.");
    }

    @Test
    void omitsFlagWhenNoPersona() {
        assertThat(runner.buildCommand(request(null), null)).doesNotContain("--append-system-prompt");
        assertThat(runner.buildCommand(request("   "), null)).doesNotContain("--append-system-prompt");
    }

    /**
     * A stand-in {@code claude}: {@code --resume} fails the way the real CLI does when the
     * conversation is gone; {@code --session-id} succeeds. Each invocation's args are logged.
     */
    @Test
    void aMissingConversationIsRetriedAsANewOneUnderTheSameSessionId(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("calls.log");
        Path fake = dir.resolve("claude");
        Files.writeString(fake, String.join("\n",
                "#!/bin/sh",
                "echo \"$*\" >> '" + log + "'",
                "case \"$*\" in",
                "  *--resume*) echo '{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
                        + "\"errors\":[\"No conversation found with session ID: s1\"]}'; exit 1 ;;",
                "  *) echo '{\"type\":\"result\",\"is_error\":false,\"result\":\"done\"}' ;;",
                "esac", ""));
        fake.toFile().setExecutable(true);

        List<AgentEvent> events = new ArrayList<>();
        new ClaudeCliAgentRunner(fake.toString(), 30).run(
                new AgentRunRequest("s1", "s1", dir.toString(), "continue", null, List.of(), false, null, null),
                events::add, new Cancellation());

        List<String> calls = Files.readAllLines(log);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).contains("--resume s1");
        assertThat(calls.get(1)).contains("--session-id s1").doesNotContain("--resume");
        assertThat(events).extracting(AgentEvent::type)
                .doesNotContain(AgentEventType.ERROR)   // the expected resume failure isn't surfaced as an error
                .contains(AgentEventType.THINKING, AgentEventType.RESULT);
    }

    @Test
    void aPoolCredentialReplacesTheServersOwnAnthropicSettings() {
        ProcessBuilder pb = new ProcessBuilder("claude");
        pb.environment().put("ANTHROPIC_API_KEY", "server-key");
        pb.environment().put("ANTHROPIC_BASE_URL", "https://server.example");
        ClaudeCliAgentRunner.applyCredential(pb, new RunCredential("c1", "https://relay.example", "relay-token", true));
        assertThat(pb.environment()).containsEntry("ANTHROPIC_BASE_URL", "https://relay.example")
                .containsEntry("ANTHROPIC_AUTH_TOKEN", "relay-token")
                .doesNotContainKey("ANTHROPIC_API_KEY"); // a leftover key would win over the token
        assertThat(pb.command()).containsExactly("claude");
    }
}
