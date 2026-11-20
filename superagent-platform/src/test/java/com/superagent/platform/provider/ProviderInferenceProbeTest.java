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

    /** A 403 that denies only the probed model must not read as a bad key, and DashScope's top-level message must show. */
    @Test
    void modelDenied403ReadsAsModelEligibilityNotABadKey() {
        String dashScope = "{\"request_id\":\"r1\",\"code\":\"AccessDenied\","
                + "\"message\":\"Access to model denied. Please make sure you are eligible for using the model.\"}";
        assertThat(ProviderInferenceProbe.cause(403, dashScope)).contains("Key 已通过验证").contains("无权使用测试的模型");
        assertThat(ProviderInferenceProbe.errorDetail(dashScope))
                .isEqualTo(": Access to model denied. Please make sure you are eligible for using the model.");

        // A key-level 403 keeps the key wording; wrapped (Anthropic/OpenAI) errors still parse; other statuses unchanged.
        assertThat(ProviderInferenceProbe.cause(403, "{\"error\":{\"message\":\"account restricted\"}}"))
                .isEqualTo("API Key 没有权限，或账号被限制");
        assertThat(ProviderInferenceProbe.errorDetail("{\"error\":{\"message\":\"invalid x-api-key\"}}"))
                .isEqualTo(": invalid x-api-key");
        assertThat(ProviderInferenceProbe.cause(401, dashScope)).isEqualTo("API Key 无效或已失效");
    }

    /** A model the endpoint retired says so; a key problem does not — the check tells them apart. */
    @Test
    void aRetiredModelIsNotMistakenForABadKey() {
        assertThat(ProviderInferenceProbe.modelGone(400,
                "{\"request_id\":\"r\",\"code\":\"InvalidParameter\",\"message\":\"Model not exist.\"}")).isTrue();
        assertThat(ProviderInferenceProbe.modelGone(404,
                "{\"error\":{\"message\":\"The model \\\"MiniMax-M2\\\" is no longer available. (2069)\"}}")).isTrue();
        assertThat(ProviderInferenceProbe.modelGone(404, "{\"error\":{\"message\":\"model_not_found\"}}")).isTrue();
        assertThat(ProviderInferenceProbe.modelGone(401,
                "{\"error\":{\"message\":\"invalid x-api-key\"}}")).isFalse();
        assertThat(ProviderInferenceProbe.modelGone(429,
                "{\"error\":{\"message\":\"rate limit exceeded\"}}")).isFalse();
    }
}
