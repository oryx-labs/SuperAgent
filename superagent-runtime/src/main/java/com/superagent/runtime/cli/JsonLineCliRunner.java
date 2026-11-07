package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.Cancellation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a CLI that prints newline-delimited JSON: start it in the workspace, feed it the prompt, turn each line into
 * events. Subclasses say how to invoke the CLI and read its lines. The CLI's own session id (which it mints itself)
 * is kept in the workspace, keyed by SuperAgent's session id, so the next turn can resume it. The key travels in the
 * environment, never argv.
 */
abstract class JsonLineCliRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(JsonLineCliRunner.class);

    protected record Line(AgentEventType type, String payload) {
    }

    /** Reads one run's output. A SYSTEM line carries the CLI's session id. */
    protected interface LineParser {
        List<Line> parse(String line);

        /** What the end of the output adds, e.g. the final result. */
        default List<Line> finish() {
            return List.of();
        }

        /** The tokens the output reported, for a run on {@code model}; empty when the CLI prints none. */
        default List<com.superagent.core.usage.TokenUsage> tokens(String model) {
            return List.of();
        }
    }

    private final String command;
    private final long timeoutSeconds;
    private CliPaths cliPaths; // null in tests: the command runs from the process PATH as given

    protected JsonLineCliRunner(String command, long timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Autowired(required = false)
    void setCliPaths(CliPaths cliPaths) {
        this.cliPaths = cliPaths;
    }

    protected final String command() {
        return command;
    }

    /** The argv; temp files it creates go in {@code cleanup}, removed when the run ends. */
    protected abstract List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException;

    /** Variables for this run: the key and endpoint. */
    protected abstract Map<String, String> environment(AgentRunRequest request) throws IOException;

    protected abstract LineParser newParser();

    /**
     * For a CLI that doesn't print its token usage but logs it: a note of where its logs stand before the run, so
     * {@link #loggedTokens} reads only what the run added. Null when there's nothing to note.
     */
    protected Object beforeRun(AgentRunRequest request) {
        return null;
    }

    /** The tokens the run logged since {@code before}; empty by default (the output carries them). */
    protected List<com.superagent.core.usage.TokenUsage> loggedTokens(AgentRunRequest request, Object before) {
        return List.of();
    }

    /** Why the run can't start (e.g. no key configured), or null. */
    protected String precondition(AgentRunRequest request) {
        return request.credential() == null ? "还没有配置 " + type().cliName() + " 的 API Key：请到 Models 页的 API Key 里添加" : null;
    }

    /** What goes on stdin; null when the prompt is in argv. */
    protected String stdin(AgentRunRequest request, String resumeId) {
        return prompt(request, resumeId);
    }

    /** These CLIs take no separate system prompt in print mode: a new conversation carries it in the first message. */
    protected static String prompt(AgentRunRequest request, String resumeId) {
        String system = resumeId == null ? request.systemPromptWithMemory() : null;
        return system == null || system.isBlank() ? request.prompt() : system + "\n\n---\n\n" + request.prompt();
    }

    /** Whether the run may change things (Claude-style allowlist): chooses the CLI's permission mode. */
    protected static boolean canWrite(AgentRunRequest request) {
        return request.skipPermissions() || (request.allowedTools() != null
                && request.allowedTools().stream().anyMatch(List.of("Edit", "Write", "Bash")::contains));
    }

    @Override
    public void run(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation) {
        long seq = 0;
        String problem = precondition(request);
        if (problem != null) {
            emit(sink, request, seq, AgentEventType.ERROR, problem);
            return;
        }
        String resumeId = request.isFirstTurn() ? null : readSession(request);
        Object logsBefore = beforeRun(request);
        List<Path> cleanup = new ArrayList<>();
        Process process;
        try {
            List<String> cmd = buildCommand(request, resumeId, cleanup);
            log.info("session {} running: {} (cwd={})", request.sessionId(), String.join(" ", cmd), request.workspaceDir());
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File(request.workspaceDir()));
            if (cliPaths != null) {
                cliPaths.apply(pb);
            }
            pb.environment().putAll(environment(request));
            process = pb.start();
            try (OutputStream in = process.getOutputStream()) {
                String stdin = stdin(request, resumeId);
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            delete(cleanup);
            emit(sink, request, seq, AgentEventType.ERROR, "Failed to start " + command + ": " + e.getMessage());
            return;
        }
        cancellation.onCancel(process::destroyForcibly);
        StringBuilder stderr = new StringBuilder();
        Thread drain = new Thread(() -> drain(process.getErrorStream(), stderr), command + "-stderr-" + request.sessionId());
        drain.setDaemon(true);
        drain.start();

        LineParser parser = newParser();
        boolean concluded = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            List<Line> parsed = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    parsed.addAll(parser.parse(line));
                }
                for (Line l : parsed) {
                    if (l.type() == AgentEventType.SYSTEM) {
                        recordSession(request, l.payload());
                    }
                    concluded |= l.type() == AgentEventType.RESULT || l.type() == AgentEventType.ERROR;
                    emit(sink, request, seq++, l.type(), l.payload());
                }
                parsed.clear();
            }
            for (Line l : parser.finish()) {
                concluded |= l.type() == AgentEventType.RESULT || l.type() == AgentEventType.ERROR;
                emit(sink, request, seq++, l.type(), l.payload());
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                emit(sink, request, seq, AgentEventType.ERROR, command + " timed out after " + timeoutSeconds + "s");
                return;
            }
            drain.join(2000);
            int code = process.exitValue();
            if (code != 0 && !concluded && !cancellation.isCancelled()) {
                String why;
                synchronized (stderr) {
                    why = stderr.toString().strip();
                }
                emit(sink, request, seq, AgentEventType.ERROR, command + " exited with code " + code + (why.isEmpty() ? "" : ": " + why));
            }
        } catch (IOException e) {
            process.destroyForcibly();
            emit(sink, request, seq, AgentEventType.ERROR, command + " run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            emit(sink, request, seq, AgentEventType.ERROR, command + " run interrupted");
        } finally {
            delete(cleanup);
            List<com.superagent.core.usage.TokenUsage> used = new ArrayList<>(parser.tokens(modelOf(request)));
            used.addAll(loggedTokens(request, logsBefore));
            Tokens.emit(sink, request.sessionId(), seq + 1, used);
        }
    }

    /** The model a run is billed under: the one requested, or the CLI's own default. */
    protected static String modelOf(AgentRunRequest request) {
        return request.model() == null || request.model().isBlank() ? "default" : request.model();
    }

    private Path sessionFile(AgentRunRequest request) {
        return Path.of(request.workspaceDir(), ".superagent", type().cliName() + "-session-" + request.sessionId());
    }

    /** The CLI's own session id for this conversation, once it has said it; null before. */
    protected String sessionOf(AgentRunRequest request) {
        return readSession(request);
    }

    private String readSession(AgentRunRequest request) {
        try {
            Path file = sessionFile(request);
            return Files.isRegularFile(file) ? Files.readString(file).strip() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void recordSession(AgentRunRequest request, String id) {
        try {
            Path file = sessionFile(request);
            Files.createDirectories(file.getParent());
            Files.writeString(file, id == null ? "" : id);
        } catch (IOException e) {
            log.warn("session {}: could not record the CLI session id: {}", request.sessionId(), e.getMessage());
        }
    }

    private static void delete(List<Path> files) {
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                log.debug("could not delete {}: {}", f, e.getMessage());
            }
        }
    }

    /** Filters Node's own warnings, which every CLI prints here and aren't an explanation of anything. */
    private static void drain(InputStream stream, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("NODE_TLS_REJECT_UNAUTHORIZED") || line.contains("--trace-warnings")) {
                    continue;
                }
                synchronized (out) {
                    out.append(line).append('\n');
                    if (out.length() > 2048) {
                        out.delete(0, out.length() - 2048);
                    }
                }
            }
        } catch (IOException e) {
            // the process went away; whatever was read stands
        }
    }

    private static void emit(AgentEventSink sink, AgentRunRequest request, long seq, AgentEventType type, String payload) {
        sink.emit(new AgentEvent(request.sessionId(), null, seq, type, payload, Instant.now()));
    }
}
