package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpGrantsTest {

    @TempDir
    Path tmp;

    @Test
    void taskGrantIsRevocableAndConfinedToItsWorkspace() throws Exception {
        McpGrants grants = new McpGrants("http://127.0.0.1:8080/mcp");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("out.md"), "ok");
        Files.writeString(tmp.resolve("secret.txt"), "no");
        Files.createSymbolicLink(ws.resolve("link.txt"), tmp.resolve("secret.txt"));

        McpGrants.Grant g = grants.openTask("ada", "t1", ws.toString());
        assertThat(grants.mcpConfig(g)).contains("Bearer " + g.token());
        assertThat(grants.workspaceFile(g, "out.md")).isEqualTo(ws.resolve("out.md").toRealPath());
        assertThatThrownBy(() -> grants.workspaceFile(g, "../secret.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.workspaceFile(g, "link.txt")).isInstanceOf(IllegalArgumentException.class);

        grants.close(g);
        assertThat(grants.find(g.token())).isEmpty();
    }

    @Test
    void connectorsJoinSuperAgentsOwnServerWithoutChangingIt() throws Exception {
        McpGrants grants = new McpGrants("http://127.0.0.1:8080/mcp");
        McpGrants.Grant g = grants.openTask("ada", "t1", tmp.toString());
        String base = grants.mcpConfig(g);

        String merged = McpGrants.withServers(base, List.of(
                new McpServerSpec("docs", ConnectorTransport.HTTP, "https://docs.example/mcp",
                        Map.of("Authorization", "Bearer d0cs"), null, List.of(), Map.of()),
                new McpServerSpec("github", ConnectorTransport.STDIO, null, Map.of(), "npx",
                        List.of("-y", "@modelcontextprotocol/server-github"), Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "gh"))));

        JsonNode servers = new ObjectMapper().readTree(merged).path("mcpServers");
        assertThat(servers.path("superagent")).isEqualTo(new ObjectMapper().readTree(base).path("mcpServers").path("superagent"));
        assertThat(servers.path("docs").path("headers").path("Authorization").asText()).isEqualTo("Bearer d0cs");
        assertThat(servers.path("github").path("command").asText()).isEqualTo("npx");
        assertThat(servers.path("github").path("env").path("GITHUB_PERSONAL_ACCESS_TOKEN").asText()).isEqualTo("gh");
        assertThat(McpGrants.withServers(base, List.of())).isEqualTo(base);
    }
}
