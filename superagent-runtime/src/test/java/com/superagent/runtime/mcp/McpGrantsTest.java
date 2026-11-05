package com.superagent.runtime.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
