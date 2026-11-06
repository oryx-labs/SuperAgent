package com.superagent.platform.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A failed CLI check says why, not just the config warnings that happen to be printed last. */
class ProviderInferenceProbeTest {

    private static final String WARNING = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"error\",\"message\":"
            + "\"Codex is ignoring 3 unrecognized configuration settings. Check for typos or deprecated settings.\\n"
            + "  user (/root/.codex/config.toml): `network_access` is ignored.\"}}";

    @Test
    void codexFailureLeadsWithTheExitCodeAndItsOwnReason() {
        String out = String.join("\n",
                "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}", WARNING, "{\"type\":\"turn.started\"}",
                "2026-11-06T16:45:16Z ERROR codex_api::endpoint::responses_websocket: failed to connect",
                "{\"type\":\"error\",\"message\":\"Reconnecting... 5/5 (unexpected status 401 Unauthorized)\"}",
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"unexpected status 401 Unauthorized: Incorrect API key provided\"}}");
        assertThat(ProviderInferenceProbe.cliFailure(1, out))
                .isEqualTo("退出码 1：unexpected status 401 Unauthorized: Incorrect API key provided");

        // Nothing after the turn started (what an administrator saw): say so instead of showing the warnings.
        String cut = String.join("\n", "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}", WARNING, "{\"type\":\"turn.started\"}");
        assertThat(ProviderInferenceProbe.cliFailure(143, cut)).startsWith("退出码 143（进程被终止）").contains("没有输出任何错误信息")
                .doesNotContain("unrecognized");

        // Plain output (Claude Code): its last lines.
        assertThat(ProviderInferenceProbe.cliFailure(1, "Starting\nError: Invalid API key · Please run /login\n"))
                .isEqualTo("退出码 1：Starting\nError: Invalid API key · Please run /login");
    }
}
