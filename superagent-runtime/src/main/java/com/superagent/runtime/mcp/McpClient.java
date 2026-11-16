package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.runtime.cli.CliPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asks an MCP server which tools it offers ({@code tools/list}), the standard way, and nothing else: no tool is
 * called, no content read. Speaks the three transports connectors use: a local command (stdio), streamable HTTP,
 * and the older HTTP+SSE.
 */
@Component
public class McpClient {

    private static final String PROTOCOL = "2025-06-18";
    private static final int MAX_PAGES = 20;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private CliPaths cliPaths; // null in tests: the command runs from the process PATH

    @Autowired(required = false)
    void setCliPaths(CliPaths cliPaths) {
        this.cliPaths = cliPaths;
    }

    /** The server's tools, each as it described itself (name, title, description, inputSchema, annotations). */
    public List<JsonNode> listTools(McpServerSpec spec, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        return switch (spec.transport() == null ? ConnectorTransport.STDIO : spec.transport()) {
            case STDIO -> stdio(spec, deadline);
            case HTTP -> streamableHttp(spec, deadline);
            case SSE -> sse(spec, deadline);
        };
    }

    // --- the conversation, whatever carries it ---

    private interface Wire {
        JsonNode request(int id, String method, JsonNode params) throws IOException;

        void notify(String method) throws IOException;
    }

    private List<JsonNode> converse(Wire wire) throws IOException {
        ObjectNode init = json.createObjectNode();
        init.put("protocolVersion", PROTOCOL);
        init.putObject("capabilities");
        init.putObject("clientInfo").put("name", "superagent").put("version", "1");
        result(wire.request(1, "initialize", init));
        wire.notify("notifications/initialized");
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode r = result(wire.request(2 + page, "tools/list", params));
            r.path("tools").forEach(tools::add);
            cursor = r.path("nextCursor").asText(null);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return tools;
    }

    private static JsonNode result(JsonNode response) throws IOException {
        if (response.has("error")) {
            throw new IOException("MCP 服务返回错误：" + response.path("error").path("message").asText(response.path("error").toString()));
        }
        return response.path("result");
    }

    private ObjectNode message(Integer id, String method, JsonNode params) {
        ObjectNode m = json.createObjectNode();
        m.put("jsonrpc", "2.0");
        if (id != null) {
            m.put("id", id);
        }
        m.put("method", method);
        if (params != null) {
            m.set("params", params);
        }
        return m;
    }

    private static long left(long deadline) throws IOException {
        long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (ms <= 0) {
            throw new IOException("MCP 服务没有及时响应");
        }
        return ms;
    }

    // --- stdio: a local command, one JSON message per line ---

    private List<JsonNode> stdio(McpServerSpec spec, long deadline) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(spec.command());
        cmd.addAll(spec.args() == null ? List.of() : spec.args());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (cliPaths != null) {
            cliPaths.apply(pb);
        }
        if (spec.env() != null) {
            pb.environment().putAll(spec.env());
        }
        Process p = pb.start();
        BlockingQueue<JsonNode> incoming = new ArrayBlockingQueue<>(256);
        Thread reader = new Thread(() -> readLines(p.getInputStream(), incoming), "mcp-list-tools");
        reader.setDaemon(true);
        reader.start();
        OutputStream out = p.getOutputStream();
        try {
            return converse(new Wire() {
                @Override
                public JsonNode request(int id, String method, JsonNode params) throws IOException {
                    sendOrExplainExit(out, message(id, method, params), p);
                    return awaitId(incoming, id, deadline, p);
                }

                @Override
                public void notify(String method) throws IOException {
                    sendOrExplainExit(out, message(null, method, null), p);
                }
            });
        } finally {
            p.destroyForcibly();
        }
    }

    private void sendOrExplainExit(OutputStream out, JsonNode m, Process p) throws IOException {
        try {
            out.write((json.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // A server that exits before reading its first request closes the pipe; reporting
            // "Stream closed" hides the exit code that explains what actually happened.
            if (!p.isAlive()) {
                throw new IOException("MCP 服务启动后就退出了（退出码 " + p.exitValue() + "）", e);
            }
            throw e;
        }
    }

    private void readLines(InputStream in, BlockingQueue<JsonNode> incoming) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("{")) {
                    try {
                        incoming.offer(json.readTree(line));
                    } catch (IOException ignored) {
                        // a log line that happens to start with a brace
                    }
                }
            }
        } catch (IOException ignored) {
            // the process ended
        }
    }

    private static JsonNode awaitId(BlockingQueue<JsonNode> incoming, int id, long deadline, Process p) throws IOException {
        while (true) {
            JsonNode m;
            try {
                m = incoming.poll(Math.min(left(deadline), 500), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (m != null && m.path("id").asInt(-1) == id) {
                return m;
            }
            if (m == null && p != null && !p.isAlive() && incoming.isEmpty()) {
                throw new IOException("MCP 服务启动后就退出了（退出码 " + p.exitValue() + "）");
            }
        }
    }

    // --- streamable HTTP: one POST per message; the answer as JSON or as an SSE stream ---

    private List<JsonNode> streamableHttp(McpServerSpec spec, long deadline) throws IOException {
        String[] session = new String[1];
        return converse(new Wire() {
            @Override
            public JsonNode request(int id, String method, JsonNode params) throws IOException {
                HttpResponse<InputStream> resp = post(spec, session[0], message(id, method, params), deadline);
                resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session[0] = s);
                if (resp.statusCode() >= 400) {
                    throw new IOException(httpFailure(resp));
                }
                String type = resp.headers().firstValue("content-type").orElse("");
                try (InputStream body = resp.body()) {
                    if (type.contains("text/event-stream")) {
                        return firstSseMatching(body, id);
                    }
                    return json.readTree(body);
                }
            }

            @Override
            public void notify(String method) throws IOException {
                HttpResponse<InputStream> resp = post(spec, session[0], message(null, method, null), deadline);
                resp.body().close();
            }
        });
    }

    private HttpResponse<InputStream> post(McpServerSpec spec, String session, JsonNode body, long deadline) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(spec.url()))
                .timeout(Duration.ofMillis(left(deadline)))
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        headers(req, spec.headers());
        if (session != null) {
            req.header("Mcp-Session-Id", session);
        }
        return send(req);
    }

    private JsonNode firstSseMatching(InputStream body, int id) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).strip());
            } else if (line.isEmpty() && data.length() > 0) {
                JsonNode m = json.readTree(data.toString());
                data.setLength(0);
                if (m.path("id").asInt(-1) == id) {
                    return m;
                }
            }
        }
        throw new IOException("MCP 服务没有返回结果");
    }

    // --- HTTP+SSE (older): GET opens the stream, which names where to POST; answers come back on the stream ---

    private List<JsonNode> sse(McpServerSpec spec, long deadline) throws IOException {
        HttpRequest.Builder open = HttpRequest.newBuilder(URI.create(spec.url())).timeout(Duration.ofMillis(left(deadline)))
                .header("accept", "text/event-stream").GET();
        headers(open, spec.headers());
        HttpResponse<InputStream> stream = send(open);
        if (stream.statusCode() >= 400) {
            throw new IOException(httpFailure(stream));
        }
        BlockingQueue<String[]> events = new ArrayBlockingQueue<>(256); // {event, data}
        Thread reader = new Thread(() -> readSse(stream.body(), events), "mcp-list-tools-sse");
        reader.setDaemon(true);
        reader.start();
        try {
            String endpoint = null;
            while (endpoint == null) {
                String[] e = poll(events, deadline);
                if ("endpoint".equals(e[0])) {
                    endpoint = URI.create(spec.url()).resolve(e[1].strip()).toString();
                }
            }
            String postTo = endpoint;
            return converse(new Wire() {
                @Override
                public JsonNode request(int id, String method, JsonNode params) throws IOException {
                    postSse(spec, postTo, message(id, method, params), deadline);
                    while (true) {
                        String[] e = poll(events, deadline);
                        if ("message".equals(e[0]) || e[0] == null) {
                            JsonNode m = json.readTree(e[1]);
                            if (m.path("id").asInt(-1) == id) {
                                return m;
                            }
                        }
                    }
                }

                @Override
                public void notify(String method) throws IOException {
                    postSse(spec, postTo, message(null, method, null), deadline);
                }
            });
        } finally {
            reader.interrupt();
            try {
                stream.body().close();
            } catch (IOException ignored) {
                // closing the stream is best effort
            }
        }
    }

    private void postSse(McpServerSpec spec, String url, JsonNode body, long deadline) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(left(deadline)))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        headers(req, spec.headers());
        HttpResponse<InputStream> resp = send(req);
        try (InputStream in = resp.body()) {
            in.readAllBytes();
        }
        if (resp.statusCode() >= 400) {
            throw new IOException("MCP 服务拒绝了请求（HTTP " + resp.statusCode() + "）");
        }
    }

    private static void readSse(InputStream in, BlockingQueue<String[]> events) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                } else if (line.isEmpty() && data.length() > 0) {
                    events.offer(new String[]{event, data.toString()});
                    event = null;
                    data.setLength(0);
                }
            }
        } catch (IOException ignored) {
            // the stream closed
        }
    }

    private static String[] poll(BlockingQueue<String[]> events, long deadline) throws IOException {
        try {
            String[] e = events.poll(left(deadline), TimeUnit.MILLISECONDS);
            if (e == null) {
                throw new IOException("MCP 服务没有及时响应");
            }
            return e;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    // --- helpers ---

    private static void headers(HttpRequest.Builder req, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(req::header);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest.Builder req) throws IOException {
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IOException e) {
            throw new IOException("连不上 MCP 服务：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String httpFailure(HttpResponse<InputStream> resp) throws IOException {
        String body;
        try (InputStream in = resp.body()) {
            body = new String(in.readNBytes(300), StandardCharsets.UTF_8).strip();
        }
        String why = resp.statusCode() == 401 || resp.statusCode() == 403 ? "授权无效或已过期，请重新授权"
                : "MCP 服务返回 HTTP " + resp.statusCode();
        return why + (body.isEmpty() ? "" : "：" + body);
    }
}
