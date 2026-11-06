package com.superagent.platform.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.RunCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class CliConfigWriterTest {

    @TempDir
    Path tmp;

    private final ObjectMapper json = new ObjectMapper();
    private final CliConfigWriter writer = new CliConfigWriter(null, null, false);

    @Test
    void claudeSettingsGetTheKeyBesideTheirOtherSettingsAndTheOriginalComesBack() throws Exception {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, "{\"model\":\"opus\",\"env\":{\"ANTHROPIC_API_KEY\":\"sk-hand-set\",\"FOO\":\"1\"}}");

        writer.writeClaude(tmp, new RunCredential("c1", "https://relay.example", "relay-token", true));
        JsonNode s = json.readTree(settings.toFile());
        assertThat(s.path("model").asText()).isEqualTo("opus");                          // other settings kept
        assertThat(s.path("env").path("FOO").asText()).isEqualTo("1");
        assertThat(s.path("env").path("ANTHROPIC_AUTH_TOKEN").asText()).isEqualTo("relay-token");
        assertThat(s.path("env").has("ANTHROPIC_API_KEY")).isFalse();                     // it would win over the token
        assertThat(s.path("env").path("ANTHROPIC_BASE_URL").asText()).isEqualTo("https://relay.example");
        assertThat(Files.getPosixFilePermissions(settings)).isEqualTo(PosixFilePermissions.fromString("rw-------"));

        writer.writeClaude(tmp, null); // no usable console entry any more: the hand-set key comes back
        JsonNode r = json.readTree(settings.toFile());
        assertThat(r.path("env").path("ANTHROPIC_API_KEY").asText()).isEqualTo("sk-hand-set");
        assertThat(r.path("env").has("ANTHROPIC_AUTH_TOKEN")).isFalse();
        assertThat(r.path("env").has("ANTHROPIC_BASE_URL")).isFalse();
    }

    @Test
    void codexGetsAnApiKeyLoginAndABaseUrlAndLosesThemWhenNothingIsUsable() throws Exception {
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, "model = \"gpt-6\"\n\n[mcp_servers.x]\nurl = \"http://x\"\n");

        writer.writeCodex(tmp, new RunCredential("c1", "https://relay.example/v1", "sk-relay", false));
        JsonNode auth = json.readTree(tmp.resolve("auth.json").toFile());
        assertThat(auth.path("auth_mode").asText()).isEqualTo("apikey");
        assertThat(auth.path("OPENAI_API_KEY").asText()).isEqualTo("sk-relay");
        assertThat(MachineCredentials.openaiBaseUrl(config)).isEqualTo("https://relay.example/v1");
        assertThat(Files.readString(config)).contains("[mcp_servers.x]").contains("model = \"gpt-6\"");

        writer.writeCodex(tmp, new RunCredential("c2", null, "sk-official", false)); // default endpoint
        assertThat(MachineCredentials.openaiBaseUrl(config)).isNull();

        writer.writeCodex(tmp, null);
        assertThat(tmp.resolve("auth.json")).doesNotExist(); // there was none before SuperAgent
        assertThat(Files.readString(config)).isEqualTo("model = \"gpt-6\"\n\n[mcp_servers.x]\nurl = \"http://x\"\n");
    }

    @Test
    void aRelaySetUpAsACodexProviderIsWhereThatMachinesKeyBelongs() throws Exception {
        Path config = tmp.resolve("relay.toml");
        Files.writeString(config, """
                model_provider = "relay"
                openai_base_url = "https://ignored.example/v1"

                [model_providers.other]
                base_url = "https://other.example/v1"

                [model_providers.relay]
                name = "relay"
                base_url = "https://relay.example/v1" # the gateway
                """);
        assertThat(MachineCredentials.codexEndpoint(config)).isEqualTo("https://relay.example/v1");
        Files.writeString(config, "openai_base_url = \"https://plain.example/v1\"\n");
        assertThat(MachineCredentials.codexEndpoint(config)).isEqualTo("https://plain.example/v1");
    }
}
