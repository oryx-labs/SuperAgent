package com.superagent.runtime.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A client for the Agent Client Protocol (ACP) over a child process's stdio: JSON-RPC 2.0,
 * one JSON object per line, on stdin and stdout.
 *
 * <p>Only what an agent host needs: {@code initialize}, {@code session/new},
 * {@code session/load}, {@code session/prompt} and {@code session/cancel}. Requests carry
 * an id and are answered by a response with the same id; the agent's own messages arrive
 * as {@code session/update} notifications, and it may ask for permission with
 * {@code session/request_permission}.
 *
 * <p>Reads run on one thread and hand each message to whoever is waiting for it, so a
 * response and a notification can arrive in any order without either blocking the other.
 */
public final class AcpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** ACP's protocol version, as Grok's agent reports it. */
    public static final int PROTOCOL_VERSION = 1;

    /** One message from the agent: a response to one of our requests, or a notification. */
    public record Message(JsonNode json) {
        public boolean isResponse() {
            return json.has("id") && (json.has("result") || json.has("error"));
        }

        public boolean isNotification() {
            return !json.has("id");
        }

        public String method() {
            return json.path("method").asText("");
        }

        public JsonNode params() {
            return json.path("params");
        }

        public long id() {
            return json.path("id").asLong(-1);
        }
    }

    /** How the agent should answer a permission request. */
    public record PermissionDecision(String optionId) {
        /** ACP's built-in option ids. */
        public static final String ALLOW_ONCE = "allow_once";
        public static final String ALLOW_ALWAYS = "allow_always";
        public static final String REJECT_ONCE = "reject_once";
        public static final String REJECT_ALWAYS = "reject_always";
    }

    private final Process process;
    private final OutputStream stdin;
    private final Thread reader;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
    private final List<Message> incoming = new ArrayList<>();
    private final Duration requestTimeout;

    public AcpClient(Process process, Duration requestTimeout) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.requestTimeout = requestTimeout;
        this.reader = new Thread(this::readLoop, "acp-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    // --- sending ---

    /** Sends a request and waits for its response. */
    public CompletableFuture<Message> request(String method, ObjectNode params) {
        long id = ids.incrementAndGet();
        CompletableFuture<Message> answer = new CompletableFuture<>();
        pending.put(id, answer);
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        try {
            synchronized (stdin) {
                stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            answer.completeExceptionally(e);
            return answer;
        }
        return answer.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((m, e) -> pending.remove(id));
    }

    /** Sends a notification (no id, no answer expected). */
    public void notify(String method, ObjectNode params) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        write(message, method);
    }

    /**
     * Answers one of the agent's own requests (for example {@code session/request_permission}
     * or {@code fs/read_text_file}). ACP carries these as JSON-RPC requests with an id; a
     * notification is not a valid answer. {@code result} may be null (ACP's write-file ack).
     */
    public void respond(long id, JsonNode result) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        if (result == null || result.isNull()) {
            message.putNull("result");
        } else {
            message.set("result", result);
        }
        write(message, "response#" + id);
    }

    /** Answers an agent request with a JSON-RPC error (e.g. a path outside the workspace). */
    public void respondError(long id, int code, String messageText) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.putObject("error").put("code", code).put("message", messageText == null ? "" : messageText);
        write(message, "error#" + id);
    }

    private void write(ObjectNode message, String label) {
        try {
            synchronized (stdin) {
                stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            log.warn("could not send {}: {}", label, e.getMessage());
        }
    }

    /**
     * The next message from the agent that isn't the answer to one of our requests — its
     * updates, and the permission requests it makes. Null when none arrives within
     * {@code timeout}.
     */
    public Message take(Duration timeout) throws InterruptedException {
        synchronized (incoming) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (incoming.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                incoming.wait(Math.max(1, left / 1_000_000), (int) (left % 1_000_000));
            }
            return incoming.remove(0);
        }
    }

    // --- receiving ---

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode json;
                try {
                    json = JSON.readTree(line);
                } catch (IOException e) {
                    log.debug("skipping unparseable ACP line: {}", e.getMessage());
                    continue;
                }
                deliver(new Message(json));
            }
        } catch (IOException e) {
            log.debug("ACP stream ended: {}", e.getMessage());
        }
        failAll(eof());
    }

    private void deliver(Message message) {
        if (message.isResponse()) {
            CompletableFuture<Message> answer = pending.remove(message.id());
            if (answer != null) {
                answer.complete(message);
            }
            return;
        }
        synchronized (incoming) {
            incoming.add(message);
            incoming.notifyAll();
        }
    }

    private void failAll(Throwable cause) {
        pending.values().forEach(f -> f.completeExceptionally(cause));
        pending.clear();
        synchronized (incoming) {
            incoming.notifyAll();
        }
    }

    private static Throwable eof() {
        return new IllegalStateException("the agent closed its connection");
    }

    /** Whether the agent is still running. */
    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // the process is going away anyway
        }
        process.destroyForcibly();
    }

    // --- helpers for building params ---

    /** An MCP server to inject into a session, in the shape ACP expects. */
    public record McpServer(String name, String type, String url, String command, java.util.List<String> args,
                            Map<String, String> headers, Map<String, String> env) {

        /** An HTTP MCP server (how SuperAgent's own endpoint is reached). */
        public static McpServer http(String name, String url, Map<String, String> headers) {
            return new McpServer(name, "http", url, null, java.util.List.of(), headers, Map.of());
        }

        /** A stdio MCP server (a company connector). */
        public static McpServer stdio(String name, String command, java.util.List<String> args,
                                      Map<String, String> env) {
            return new McpServer(name, "stdio", null, command, args, Map.of(), env);
        }

        ObjectNode toJson() {
            ObjectNode node = JSON.createObjectNode();
            node.put("type", type);
            node.put("name", name);
            if (url != null) {
                node.put("url", url);
            }
            if (command != null) {
                node.put("command", command);
            }
            if (args != null && !args.isEmpty()) {
                node.set("args", JSON.valueToTree(args));
            }
            if (headers != null && !headers.isEmpty()) {
                var array = node.putArray("headers");
                headers.forEach((name2, value) -> array.addObject().put("name", name2).put("value", value));
            }
            if (env != null && !env.isEmpty()) {
                ObjectNode envNode = node.putObject("env");
                env.forEach(envNode::put);
            }
            return node;
        }
    }

    /** The MCP servers of a session request. */
    public static ObjectNode mcpServers(java.util.List<McpServer> servers) {
        ObjectNode params = JSON.createObjectNode();
        var array = params.putArray("mcpServers");
        servers.forEach(s -> array.add(s.toJson()));
        return params;
    }

    static UncheckedIOException wrap(IOException e) {
        return new UncheckedIOException(e);
    }
}
