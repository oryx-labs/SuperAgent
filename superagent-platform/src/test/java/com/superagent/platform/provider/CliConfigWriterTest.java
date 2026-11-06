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
    void codexMovesOffAHandSetRelayWhileAConsoleKeyIsInUseAndGoesBackAfter() throws Exception {
        Path config = tmp.resolve("config.toml");
        String original = """
                model_provider = "OpenAI"
                model = "gpt-6"
                profile = "relay"

                [model_providers.OpenAI]
                name = "OpenAI"
                base_url = "https://api.relay.example"
                wire_api = "responses"
                requires_openai_auth = true

                [profiles.relay]
                model_provider = "OpenAI"
                """;
        Files.writeString(config, original);

        writer.writeCodex(tmp, new RunCredential("c1", "https://api.openai.com/v1", "sk-console", false));
        JsonNode auth = json.readTree(tmp.resolve("auth.json").toFile());
        assertThat(auth.path("auth_mode").asText()).isEqualTo("apikey");
        assertThat(auth.path("OPENAI_API_KEY").asText()).isEqualTo("sk-console");
        assertThat(CliConfigWriter.topLevel(config, "model_provider")).isEqualTo("\"openai\"");
        assertThat(CliConfigWriter.topLevel(config, "openai_base_url")).isEqualTo("\"https://api.openai.com/v1\"");
        assertThat(CliConfigWriter.topLevel(config, "profile")).isNull();
        // The hand-made relay is still there, just not selected.
        assertThat(Files.readString(config)).contains("[model_providers.OpenAI]").contains("model = \"gpt-6\"");

        // A failover to another entry: the config follows.
        writer.writeCodex(tmp, new RunCredential("c2", "https://backup.example/v1", "sk-backup", false));
        assertThat(CliConfigWriter.topLevel(config, "openai_base_url")).isEqualTo("\"https://backup.example/v1\"");
        assertThat(json.readTree(tmp.resolve("auth.json").toFile()).path("OPENAI_API_KEY").asText()).isEqualTo("sk-backup");

        // Nothing usable any more: the machine's own setup comes back as it was.
        writer.writeCodex(tmp, null);
        assertThat(tmp.resolve("auth.json")).doesNotExist(); // there was none before SuperAgent
        assertThat(CliConfigWriter.topLevel(config, "model_provider")).isEqualTo("\"OpenAI\"");
        assertThat(CliConfigWriter.topLevel(config, "profile")).isEqualTo("\"relay\"");
        assertThat(CliConfigWriter.topLevel(config, "openai_base_url")).isNull();
        assertThat(Files.readString(config)).contains("[profiles.relay]").contains("requires_openai_auth = true");
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
