package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.Cancellation;
import com.superagent.runtime.mcp.McpGrants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps a conversation's Claude Code running between its turns. Starting the CLI (Node, MCP connections, reloading
 * the conversation) takes seconds on every turn; a kept one reads the next message from stdin
 * ({@code --input-format stream-json}) and answers at once. One per session, closed after it sits idle, when the
 * pool is full, or as soon as anything it was started with changes (model, key, prompt, tools, connectors).
 *
 * <p>The MCP token is fixed when the CLI starts, but grants are per turn: during a turn the CLI's token stands in for
 * that turn's grant ({@link McpGrants#standIn}), and between turns it stands for nothing.
 *
 * <p>Whatever can't be done kept (it fails to start, the conversation is gone, the user stops the turn) falls back to,
 * or leaves the next turn to, an ordinary run; nothing here changes what a turn does, only how fast it starts.
 */
final class ClaudeWarmSessions {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWarmSessions.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NO_CONVERSATION = "No conversation found with session ID";

    private final ClaudeCliAgentRunner runner;
    private final McpGrants grants; // null in tests without MCP
    private final Duration idle;
    private final int max;
    /** Idle kept CLIs by session, least recently used first; a CLI is taken out while it runs a turn. */
    private final Map<String, Kept> idleBySession = new LinkedHashMap<>(16, 0.75f, true);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "claude-kept");
        t.setDaemon(true);
        return t;
    });

    ClaudeWarmSessions(ClaudeCliAgentRunner runner, McpGrants grants, Duration idle, int max) {
        this.runner = runner;
        this.grants = grants;
        this.idle = idle;
        this.max = Math.max(1, max);
        timer.scheduleWithFixedDelay(this::closeIdle, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Runs the turn on the session's kept CLI, starting one if needed. False when nothing was done, so the caller
     * runs the turn the ordinary way.
     */
    boolean run(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation) {
        String fingerprint = fingerprint(request);
        Kept kept;
        synchronized (idleBySession) {
            kept = idleBySession.remove(request.sessionId());
        }
        if (kept != null && (!kept.alive() || !kept.fingerprint.equals(fingerprint))) {
            log.info("session {}: kept claude {} is stale (it exited, or the run changed); starting another",
                    request.sessionId(), kept.pid());
            kept.close();
            kept = null;
        }
        if (kept == null) {
            try {
                kept = start(request, fingerprint);
            } catch (IOException e) {
                log.warn("session {}: could not keep claude running ({}); running the turn the ordinary way",
                        request.sessionId(), e.getMessage());
                return false;
            }
        } else {
            log.info("session {}: reusing kept claude {}", request.sessionId(), kept.pid());
        }
        Outcome outcome = kept.turn(request, sink, cancellation);
        if (outcome == Outcome.NOTHING && !cancellation.isCancelled()) {
            kept.close(); // e.g. the conversation to resume is gone: the ordinary run knows what to do about it
            return false;
        }
        if (outcome == Outcome.DONE && kept.alive() && !cancellation.isCancelled()) {
            keep(request.sessionId(), kept);
        } else {
            kept.close();
        }
        return true;
    }

    void closeAll() {
        List<Kept> all;
        synchronized (idleBySession) {
            all = new ArrayList<>(idleBySession.values());
            idleBySession.clear();
        }
        all.forEach(Kept::close);
        timer.shutdownNow();
    }

    int idleCount() {
        synchronized (idleBySession) {
            return idleBySession.size();
        }
    }

    private void keep(String sessionId, Kept kept) {
        kept.lastUsed = Instant.now();
        List<Kept> evicted = new ArrayList<>();
        synchronized (idleBySession) {
            idleBySession.put(sessionId, kept);
            var it = idleBySession.values().iterator();
            while (idleBySession.size() > max && it.hasNext()) {
                evicted.add(it.next()); // least recently used first
                it.remove();
            }
        }
        evicted.forEach(Kept::close);
    }

    private void closeIdle() {
        Instant cutoff = Instant.now().minus(idle);
        List<Kept> expired = new ArrayList<>();
        synchronized (idleBySession) {
            idleBySession.values().removeIf(k -> {
                boolean gone = k.lastUsed.isBefore(cutoff) || !k.alive();
                if (gone) {
                    expired.add(k);
                }
                return gone;
            });
        }
        expired.forEach(Kept::close);
    }

    private Kept start(AgentRunRequest request, String fingerprint) throws IOException {
        Path settings = ClaudeCliAgentRunner.writeRunSettings(request);
        Path mcp = runner.writeMcpConfig(request);
        List<String> cmd = runner.buildCommand(request, mcp, settings, true);
        Process process;
        try {
            process = runner.processFor(cmd, request).start();
        } catch (IOException e) {
            runner.deleteQuietly(settings);
            runner.deleteQuietly(mcp);
            throw e;
        }
        log.info("session {}: started kept claude {} ({})", request.sessionId(), process.pid(), String.join(" ", cmd));
        return new Kept(process, fingerprint, McpGrants.tokenIn(request.mcpConfig()), settings, mcp, request.sessionId());
    }

    /**
     * What the CLI was started with, except what differs from turn to turn by design: the message, the memory a
     * conversation starts from, whether it's resuming, and SuperAgent's own MCP token (it stands in for each turn's).
     */
    static String fingerprint(AgentRunRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.workspaceDir()).append('\n').append(request.model()).append('\n')
                .append(request.systemPrompt()).append('\n').append(request.allowedTools()).append('\n')
                .append(request.skipPermissions()).append('\n').append(!request.images().isEmpty()).append('\n');
        String token = McpGrants.tokenIn(request.mcpConfig());
        String mcp = request.mcpConfig() == null ? "" : request.mcpConfig();
        sb.append(token == null ? mcp : mcp.replace(token, "")).append('\n');
        var c = request.credential();
        if (c != null) {
            sb.append(c.baseUrl()).append('\n').append(c.bearer()).append('\n').append(c.apiKey());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8))); // never keep a key around in clear
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private enum Outcome { DONE, NOTHING, BROKEN }

    /** One kept CLI: its process, the turn-by-turn reader of its output, and what it was started with. */
    private final class Kept {
        private final Process process;
        private final String fingerprint;
        private final String token;
        private final Path settings;
        private final Path mcp;
        private final BufferedReader out;
        private final OutputStream in;
        private final StringBuilder stderrTail = new StringBuilder();
        private String conversation;
        private volatile Instant lastUsed = Instant.now();
        /**
         * What the CLI has reported so far. Its results carry running totals for the whole process (cost and per-model
         * tokens), so each turn is billed the difference from the last one, not the total again.
         */
        private final Map<String, com.superagent.core.usage.TokenUsage> reported = new java.util.HashMap<>();
        private double reportedCost;

        Kept(Process process, String fingerprint, String token, Path settings, Path mcp, String sessionId) {
            this.process = process;
            this.fingerprint = fingerprint;
            this.token = token;
            this.settings = settings;
            this.mcp = mcp;
            this.out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.in = process.getOutputStream();
            Thread stderr = new Thread(() -> runner.drainStderr(process.getErrorStream(), sessionId, stderrTail),
                    "claude-kept-stderr-" + sessionId);
            stderr.setDaemon(true);
            stderr.start();
        }

        long pid() {
            return process.pid();
        }

        boolean alive() {
            return process.isAlive();
        }

        Outcome turn(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation) {
            AtomicLong seq = new AtomicLong();
            String standing = token;
            String current = McpGrants.tokenIn(request.mcpConfig());
            if (grants != null && standing != null && current != null) {
                grants.standIn(standing, current);
            }
            cancellation.onCancel(this::close);
            ScheduledFuture<?> deadline = timer.schedule(this::close, runner.timeoutSeconds(), TimeUnit.SECONDS);
            ClaudeStreamParser parser = new ClaudeStreamParser();
            boolean said = false;
            try {
                in.write(message(request).getBytes(StandardCharsets.UTF_8));
                in.flush();
                if (conversation != null) {
                    // Already running: the turn starts now (this is also when its timing stops counting the start).
                    sink.emit(event(request, seq, AgentEventType.SYSTEM, conversation));
                }
                String line;
                while ((line = out.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Optional<ClaudeStreamParser.ParsedEvent> parsed = parser.parse(line);
                    if (parsed.isEmpty()) {
                        continue;
                    }
                    ClaudeStreamParser.ParsedEvent e = parsed.get();
                    if (e.type() == AgentEventType.ERROR && !said && e.payload().contains(NO_CONVERSATION)) {
                        return Outcome.NOTHING;
                    }
                    if (e.type() == AgentEventType.SYSTEM) {
                        conversation = e.payload();
                    } else {
                        said = true;
                    }
                    for (String denial : e.permissionDenials()) {
                        sink.emit(event(request, seq, AgentEventType.TOOL_RESULT, "Permission denied: " + denial));
                    }
                    sink.emit(event(request, seq, e.type(), e.payload()));
                    if (e.costUsd() != null) {
                        double turnCost = Math.max(0, e.costUsd() - reportedCost);
                        reportedCost = Math.max(reportedCost, e.costUsd());
                        sink.emit(event(request, seq, AgentEventType.USAGE, String.valueOf(turnCost)));
                    }
                    if (!e.tokens().isEmpty()) {
                        Tokens.emit(sink, request.sessionId(), seq.getAndIncrement(),
                                ClaudeCliAgentRunner.withModel(sinceLastResult(e.tokens()), request.model()));
                    }
                    if (e.type() == AgentEventType.RESULT || e.type() == AgentEventType.ERROR) {
                        return Outcome.DONE; // the turn is over; the CLI waits for the next message
                    }
                }
                // The CLI exited mid-turn.
                if (!said) {
                    return Outcome.NOTHING;
                }
                if (!cancellation.isCancelled()) {
                    String why;
                    synchronized (stderrTail) {
                        why = stderrTail.toString().strip();
                    }
                    sink.emit(event(request, seq, AgentEventType.ERROR, deadline.isDone()
                            ? "Agent timed out after " + runner.timeoutSeconds() + "s"
                            : "claude exited before finishing" + (why.isEmpty() ? "" : ": " + why)));
                }
                return Outcome.BROKEN;
            } catch (IOException e) {
                if (!said) {
                    return Outcome.NOTHING;
                }
                if (!cancellation.isCancelled()) {
                    sink.emit(event(request, seq, AgentEventType.ERROR, "Agent run failed: " + e.getMessage()));
                }
                return Outcome.BROKEN;
            } finally {
                deadline.cancel(false);
                cancellation.onCancel(null);
                if (grants != null && standing != null) {
                    grants.release(standing);
                }
                if (said) {
                    // Stopped or broken before its result: what the calls so far used is still billed.
                    Tokens.emit(sink, request.sessionId(), seq.getAndIncrement(),
                            ClaudeCliAgentRunner.withModel(parser.unreportedTokens(), request.model()));
                }
            }
        }

        /** This turn's share of the running totals a result reports. */
        private List<com.superagent.core.usage.TokenUsage> sinceLastResult(List<com.superagent.core.usage.TokenUsage> totals) {
            List<com.superagent.core.usage.TokenUsage> turn = new ArrayList<>();
            for (var t : totals) {
                var before = reported.get(String.valueOf(t.model()));
                reported.put(String.valueOf(t.model()), t);
                var d = before == null ? t : new com.superagent.core.usage.TokenUsage(t.model(),
                        Math.max(0, t.input() - before.input()), Math.max(0, t.cacheRead() - before.cacheRead()),
                        Math.max(0, t.cacheWrite() - before.cacheWrite()), Math.max(0, t.cacheWrite1h() - before.cacheWrite1h()),
                        Math.max(0, t.output() - before.output()), Math.max(0, t.reasoning() - before.reasoning()),
                        t.cliCostUsd() == null ? null
                                : Math.max(0, t.cliCostUsd() - (before.cliCostUsd() == null ? 0 : before.cliCostUsd())));
                if (!d.nothingUsed()) {
                    turn.add(d);
                }
            }
            return turn;
        }

        private String message(AgentRunRequest request) throws IOException {
            if (!request.images().isEmpty()) {
                return runner.stdinMessage(request);
            }
            ObjectNode message = JSON.createObjectNode();
            message.put("type", "user");
            ObjectNode inner = message.putObject("message");
            inner.put("role", "user");
            inner.putArray("content").addObject().put("type", "text").put("text", request.prompt() == null ? "" : request.prompt());
            return JSON.writeValueAsString(message) + "\n";
        }

        void close() {
            try {
                in.close(); // the CLI ends when its input does
            } catch (IOException ignored) {
                // already gone
            }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            runner.deleteQuietly(settings);
            runner.deleteQuietly(mcp);
        }
    }

    private static AgentEvent event(AgentRunRequest request, AtomicLong seq, AgentEventType type, String payload) {
        return new AgentEvent(request.sessionId(), null, seq.getAndIncrement(), type, payload, Instant.now());
    }
}
