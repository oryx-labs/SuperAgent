package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reads a server's tool list the standard way, over a local command and over streamable HTTP. */
class McpClientTest {

    private final McpClient client = new McpClient();

    @Test
    void stdioServerToolsAcrossPagesWithTheTokenFromEnv(@TempDir Path dir) throws IOException {
        // Answers by line order: initialize, (notification), tools/list page 1, page 2. Logs noise on stdout too.
        Path server = dir.resolve("server.sh");
        Files.writeString(server, """
                #!/bin/sh
                read l; echo 'starting up...'; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}'
                read l
                read l; echo '{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"list_repos","annotations":{"readOnlyHint":true}}],"nextCursor":"p2"}}'
                read l; echo "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":3,\\"result\\":{\\"tools\\":[{\\"name\\":\\"whoami_$TOKEN\\"}]}}"
                read l
                """);
        server.toFile().setExecutable(true);

        List<JsonNode> tools = client.listTools(new McpServerSpec("x", ConnectorTransport.STDIO, null, Map.of(),
                server.toString(), List.of(), Map.of("TOKEN", "ada")), Duration.ofSeconds(10));

        assertThat(tools).extracting(t -> t.path("name").asText()).containsExactly("list_repos", "whoami_ada");
        assertThat(tools.get(0).path("annotations").path("readOnlyHint").asBoolean()).isTrue();

        Path dies = dir.resolve("dies.sh");
        Files.writeString(dies, "#!/bin/sh\nexit 3\n");
        dies.toFile().setExecutable(true);
        assertThatThrownBy(() -> client.listTools(new McpServerSpec("x", ConnectorTransport.STDIO, null, Map.of(),
                dies.toString(), List.of(), Map.of()), Duration.ofSeconds(10))).hasMessageContaining("退出");
    }

    @Test
    void streamableHttpKeepsTheSessionAndReadsSseAnswers() throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<String> seen = new ArrayList<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", ex -> {
            JsonNode m = json.readTree(ex.getRequestBody());
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            seen.add(m.path("method").asText() + "|" + ex.getRequestHeaders().getFirst("Mcp-Session-Id"));
            if (!"Bearer good".equals(auth)) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            if (!m.has("id")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            String body;
            if ("initialize".equals(m.path("method").asText())) {
                ex.getResponseHeaders().add("Mcp-Session-Id", "s1");
                ex.getResponseHeaders().add("Content-Type", "application/json");
                body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
            } else {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + m.path("id").asInt()
                        + ",\"result\":{\"tools\":[{\"name\":\"create_issue\",\"annotations\":{\"destructiveHint\":false}}]}}\n\n";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
            List<JsonNode> tools = client.listTools(new McpServerSpec("x", ConnectorTransport.HTTP, url,
                    Map.of("Authorization", "Bearer good"), null, List.of(), Map.of()), Duration.ofSeconds(10));
            assertThat(tools).extracting(t -> t.path("name").asText()).containsExactly("create_issue");
            assertThat(seen).containsExactly("initialize|null", "notifications/initialized|s1", "tools/list|s1");

            assertThatThrownBy(() -> client.listTools(new McpServerSpec("x", ConnectorTransport.HTTP, url,
                    Map.of("Authorization", "Bearer stale"), null, List.of(), Map.of()), Duration.ofSeconds(10)))
                    .hasMessageContaining("重新授权");
        } finally {
            http.stop(0);
        }
    }
}
