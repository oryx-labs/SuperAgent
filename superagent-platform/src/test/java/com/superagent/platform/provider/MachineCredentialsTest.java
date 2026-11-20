package com.superagent.platform.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The model Claude Code is configured to run — what a relay without a model list is offered instead. */
class MachineCredentialsTest {

    @TempDir
    Path tmp;

    private MachineCredentials with(Map<String, String> env, String settingsJson) throws Exception {
        Path claudeDir = tmp.resolve("claude");
        Files.createDirectories(claudeDir);
        if (settingsJson != null) {
            Files.writeString(claudeDir.resolve("settings.json"), settingsJson);
        }
        return new MachineCredentials(env::get, claudeDir, tmp.resolve("codex"));
    }

    @Test
    void theModelComesFromTheCliSettingsAndLosesItsContextWindowHint() throws Exception {
        MachineCredentials mc = with(Map.of(),
                "{\"env\":{\"ANTHROPIC_MODEL\":\"MiniMax-M3[1m]\",\"ANTHROPIC_AUTH_TOKEN\":\"sk-x\"}}");
        assertThat(mc.claudeModel()).isEqualTo("MiniMax-M3");
    }

    @Test
    void theEnvironmentWinsOverTheSettings() throws Exception {
        MachineCredentials mc = with(Map.of("ANTHROPIC_MODEL", "from-env"),
                "{\"env\":{\"ANTHROPIC_MODEL\":\"from-settings\"}}");
        assertThat(mc.claudeModel()).isEqualTo("from-env");
    }

    @Test
    void noModelAnywhereIsNoModel() throws Exception {
        assertThat(with(Map.of(), null).claudeModel()).isNull();
    }
}
