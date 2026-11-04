package com.superagent.worker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ImageAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AgentRunner} for Claude Code. Spawns the {@code claude} CLI in
 * non-interactive streaming mode, reads its NDJSON output line by line, and
 * emits normalised {@link AgentEvent}s as they arrive.
 *
 * <p>Session continuity: the first turn passes {@code --session-id} using the
 * SuperAgent session id (a UUID), so the Claude session id equals ours;
 * follow-up turns pass {@code --resume} to continue the same conversation in the
 * same working directory.
 */
@Component
public class ClaudeCliAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliAgentRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String command;
    private final long timeoutSeconds;
    private final ClaudeStreamParser parser = new ClaudeStreamParser();
    /** Claude's error when {@code --resume} names a session it has no conversation for. */
    private static final String NO_CONVERSATION = "No conversation found with session ID";

    public ClaudeCliAgentRunner(
            @Value("${superagent.agent.claude.command:claude}") String command,
            @Value("${superagent.agent.claude.timeout-seconds:600}") long timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AgentType type() {
        return AgentType.CLAUDE;
    }

    @Override
    public void run(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation) {
        AtomicLong seq = new AtomicLong();
        if (attempt(request, sink, cancellation, seq) && !cancellation.isCancelled()) {
            // The session's conversation is gone from this Claude config (cleaned up, or written under
            // another config dir). Start a new conversation under the SAME session id so the task keeps
            // its session and workspace; only the earlier conversation context is lost.
            log.warn("session {}: no conversation to resume; starting a new one under the same session id",
                    request.sessionId());
            sink.emit(new AgentEvent(request.sessionId(), null, seq.getAndIncrement(), AgentEventType.THINKING,
                    "Previous conversation for this session was not found; continuing in a new conversation "
                            + "(same session id, same workspace — earlier context is not available).",
                    Instant.now()));
            AgentRunRequest fresh = new AgentRunRequest(request.sessionId(), null, request.workspaceDir(),
                    request.prompt(), request.model(), request.allowedTools(), request.skipPermissions(),
                    request.mcpConfig(), request.systemPrompt(), request.images(), request.memory());
            attempt(fresh, sink, cancellation, seq);
        }
    }

    /** One CLI invocation. Returns true when a resume failed only because the conversation doesn't exist. */
    private boolean attempt(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation, AtomicLong seq) {
        boolean conversationMissing = false;
        String stdinMessage;
        try {
            stdinMessage = request.images().isEmpty() ? null : stdinMessage(request);
        } catch (IOException e) {
            emitError(sink, request, seq.getAndIncrement(), "Cannot read attached image: " + e.getMessage());
            return false;
        }
        Path mcpConfigFile = writeMcpConfig(request);
        List<String> cmd = buildCommand(request, mcpConfigFile);
        // Never log the prompt: with images it's on stdin; otherwise it's the last argv entry.
        log.info("session {} running: {} (cwd={}, images={})", request.sessionId(),
                String.join(" ", stdinMessage != null ? cmd : cmd.subList(0, cmd.size() - 1)),
                request.workspaceDir(), request.images().size());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new File(request.workspaceDir()))
                .redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
            if (stdinMessage != null) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(stdinMessage.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            emitError(sink, request, seq.getAndIncrement(), "Failed to start agent: " + e.getMessage());
            deleteQuietly(mcpConfigFile);
            return false;
        }

        // Let the orchestrator stop this turn by killing the subprocess.
        cancellation.onCancel(process::destroyForcibly);

        Thread stderr = new Thread(() -> drainStderr(process.getErrorStream(), request.sessionId()),
                "claude-stderr-" + request.sessionId());
        stderr.setDaemon(true);
        stderr.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Optional<ClaudeStreamParser.ParsedEvent> parsed = parser.parse(line);
                if (parsed.isEmpty()) {
                    continue;
                }
                ClaudeStreamParser.ParsedEvent e = parsed.get();
                if (e.type() == AgentEventType.ERROR && !request.isFirstTurn()
                        && e.payload().contains(NO_CONVERSATION)) {
                    conversationMissing = true; // handled by run(): retried as a new conversation
                    continue;
                }
                for (String denial : e.permissionDenials()) {
                    sink.emit(new AgentEvent(request.sessionId(), null, seq.getAndIncrement(),
                            AgentEventType.TOOL_RESULT, "Permission denied: " + denial, Instant.now()));
                }
                sink.emit(new AgentEvent(request.sessionId(), null, seq.getAndIncrement(), e.type(), e.payload(), Instant.now()));
                if (e.costUsd() != null) {
                    sink.emit(new AgentEvent(request.sessionId(), null, seq.getAndIncrement(),
                            AgentEventType.USAGE, String.valueOf(e.costUsd()), Instant.now()));
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                emitError(sink, request, seq.getAndIncrement(), "Agent timed out after " + timeoutSeconds + "s");
                return false;
            }
            int code = process.exitValue();
            if (code != 0) {
                log.warn("session {} claude exited with code {}", request.sessionId(), code);
            }
        } catch (IOException e) {
            process.destroyForcibly();
            emitError(sink, request, seq.getAndIncrement(), "Agent run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            emitError(sink, request, seq.getAndIncrement(), "Agent run interrupted");
        } finally {
            deleteQuietly(mcpConfigFile);
        }
        return conversationMissing;
    }

    // Package-private for unit testing of the argv assembly (persona injection, flag ordering).
    List<String> buildCommand(AgentRunRequest request, Path mcpConfigFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--include-partial-messages");
        cmd.add("--verbose");

        if (request.model() != null && !request.model().isBlank()) {
            cmd.add("--model");
            cmd.add(request.model());
        }

        // A new conversation also gets the agent's memory; a resumed one already has its history.
        String systemPrompt = request.isFirstTurn() ? request.systemPromptWithMemory() : request.systemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPrompt);
        }

        if (mcpConfigFile != null) {
            cmd.add("--mcp-config");
            cmd.add(mcpConfigFile.toString());
        }

        if (request.isFirstTurn()) {
            cmd.add("--session-id");
            cmd.add(request.sessionId());
        } else {
            cmd.add("--resume");
            cmd.add(request.agentSessionId());
        }

        if (request.skipPermissions()) {
            log.warn("session {} starting with --dangerously-skip-permissions: the agent may run any tool "
                    + "without approval. Enable superagent.agent.skip-permissions only in an isolated sandbox.",
                    request.sessionId());
            cmd.add("--dangerously-skip-permissions");
        } else if (request.allowedTools() != null && !request.allowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(" ", request.allowedTools()));
        }

        if (!request.images().isEmpty()) {
            // Images can only travel as content blocks, so the whole message goes on stdin instead.
            cmd.add("--input-format");
            cmd.add("stream-json");
            // The Claude gateway rejects image requests from a CLI with Artifacts on ("request is not
            // representable on this edge"); turn it off for this run only, not in the host's settings.
            cmd.add("--settings");
            cmd.add("{\"enableArtifact\":false}");
            return cmd;
        }
        // End-of-options separator: the CLI treats everything after "--" as a
        // positional argument, so a user prompt that begins with "-" or "--"
        // cannot be smuggled in as a CLI flag (argv flag injection).
        cmd.add("--");
        cmd.add(request.prompt());
        return cmd;
    }

    /** The prompt plus its images as one stream-json user message (one line, then stdin closes). */
    String stdinMessage(AgentRunRequest request) throws IOException {
        ObjectNode message = JSON.createObjectNode();
        message.put("type", "user");
        ObjectNode inner = message.putObject("message");
        inner.put("role", "user");
        ArrayNode content = inner.putArray("content");
        for (ImageAttachment image : request.images()) {
            ObjectNode source = content.addObject().put("type", "image").putObject("source");
            source.put("type", "base64");
            source.put("media_type", image.mediaType());
            source.put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(image.path()))));
        }
        if (request.prompt() != null && !request.prompt().isBlank()) {
            content.addObject().put("type", "text").put("text", request.prompt());
        }
        return JSON.writeValueAsString(message) + "\n";
    }

    private void emitError(AgentEventSink sink, AgentRunRequest request, long seq, String message) {
        log.error("session {} agent error: {}", request.sessionId(), message);
        sink.emit(new AgentEvent(request.sessionId(), null, seq, AgentEventType.ERROR, message, Instant.now()));
    }

    /** Writes the request's MCP config (if any) to a temp file the CLI reads via {@code --mcp-config}. */
    private Path writeMcpConfig(AgentRunRequest request) {
        if (request.mcpConfig() == null || request.mcpConfig().isBlank()) {
            return null;
        }
        try {
            Path file = Files.createTempFile("superagent-mcp-", ".json");
            Files.writeString(file, request.mcpConfig(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            log.warn("session {} could not write mcp config, continuing without it: {}",
                    request.sessionId(), e.getMessage());
            return null;
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("could not delete mcp config temp file {}: {}", file, e.getMessage());
        }
    }

    private void drainStderr(InputStream stream, String sessionId) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("session {} claude stderr: {}", sessionId, line);
            }
        } catch (IOException e) {
            log.debug("session {} stderr drain ended: {}", sessionId, e.getMessage());
        }
    }
}
