package com.superagent.worker.agent;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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

    private final String command;
    private final long timeoutSeconds;
    private final ClaudeStreamParser parser = new ClaudeStreamParser();

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
        Path mcpConfigFile = writeMcpConfig(request);
        List<String> cmd = buildCommand(request, mcpConfigFile);
        log.info("session {} running: {} (cwd={})", request.sessionId(),
                String.join(" ", cmd.subList(0, cmd.size() - 1)), request.workspaceDir());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new File(request.workspaceDir()))
                .redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            emitError(sink, request, 0, "Failed to start agent: " + e.getMessage());
            deleteQuietly(mcpConfigFile);
            return;
        }

        // Let the orchestrator stop this turn by killing the subprocess.
        cancellation.onCancel(process::destroyForcibly);

        Thread stderr = new Thread(() -> drainStderr(process.getErrorStream(), request.sessionId()),
                "claude-stderr-" + request.sessionId());
        stderr.setDaemon(true);
        stderr.start();

        long seq = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Optional<ClaudeStreamParser.ParsedEvent> parsed = parser.parse(line);
                if (parsed.isPresent()) {
                    ClaudeStreamParser.ParsedEvent e = parsed.get();
                    sink.emit(new AgentEvent(request.sessionId(), null, seq++, e.type(), e.payload(), Instant.now()));
                    if (e.costUsd() != null) {
                        sink.emit(new AgentEvent(request.sessionId(), null, seq++,
                                AgentEventType.USAGE, String.valueOf(e.costUsd()), Instant.now()));
                    }
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                emitError(sink, request, seq, "Agent timed out after " + timeoutSeconds + "s");
                return;
            }
            int code = process.exitValue();
            if (code != 0) {
                log.warn("session {} claude exited with code {}", request.sessionId(), code);
            }
        } catch (IOException e) {
            process.destroyForcibly();
            emitError(sink, request, seq, "Agent run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            emitError(sink, request, seq, "Agent run interrupted");
        } finally {
            deleteQuietly(mcpConfigFile);
        }
    }

    private List<String> buildCommand(AgentRunRequest request, Path mcpConfigFile) {
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

        // End-of-options separator: the CLI treats everything after "--" as a
        // positional argument, so a user prompt that begins with "-" or "--"
        // cannot be smuggled in as a CLI flag (argv flag injection).
        cmd.add("--");
        cmd.add(request.prompt());
        return cmd;
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
