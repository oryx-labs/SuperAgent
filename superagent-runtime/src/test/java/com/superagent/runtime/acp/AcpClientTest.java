package com.superagent.runtime.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AcpClientTest {

    @Test
    void stdioServersSerializeEnvAsNameValueArrayAndKeepEmptyArgs() {
        JsonNode json = AcpClient.McpServer.stdio("corp", "/bin/mcp", List.of(), Map.of("TOKEN", "secret")).toJson();

        assertThat(json.path("type").asText()).isEqualTo("stdio");
        assertThat(json.path("command").asText()).isEqualTo("/bin/mcp");
        assertThat(json.path("args").isArray()).isTrue();
        assertThat(json.path("args")).isEmpty();
        assertThat(json.path("env").isArray()).isTrue();
        assertThat(json.path("env")).hasSize(1);
        assertThat(json.path("env").get(0).path("name").asText()).isEqualTo("TOKEN");
        assertThat(json.path("env").get(0).path("value").asText()).isEqualTo("secret");
        assertThat(json.path("env").isObject()).isFalse();
    }

    @Test
    void stdioServersPreserveArgsAndMultipleEnvEntries() {
        JsonNode json = AcpClient.McpServer.stdio(
                "corp", "/bin/mcp", List.of("--port", "9"), Map.of("A", "1", "B", "2")).toJson();

        assertThat(json.path("args")).extracting(JsonNode::asText).containsExactly("--port", "9");
        assertThat(json.path("env")).extracting(n -> n.path("name").asText() + "=" + n.path("value").asText())
                .containsExactlyInAnyOrder("A=1", "B=2");
    }
}
