package com.superagent.api;

/**
 * HTTP path constants shared by controllers and clients.
 *
 * <p>All endpoints are versioned by path. A breaking change introduces a new
 * version constant rather than altering an existing one, so older clients keep
 * working against the old prefix.
 */
public final class ApiPaths {

    /** Version prefix for every public endpoint. */
    public static final String V1 = "/api/v1";

    public static final String META = V1 + "/meta";

    public static final String SESSIONS = V1 + "/sessions";

    public static final String AGENTS = V1 + "/agents";

    public static final String CONNECTORS = V1 + "/connectors";

    public static final String TASKS = V1 + "/tasks";

    public static final String AUTH = V1 + "/auth";

    public static final String USERS = V1 + "/users";

    /** Model-vendor CLIs (claude, codex, ...) the runtime can call. */
    public static final String PROVIDERS = V1 + "/providers";

    /** Platform-wide default configuration (e.g. the Agent chat model). */
    public static final String SETTINGS = V1 + "/settings";

    /** OAuth authorize/callback endpoints for connectors that use a provider handshake. */
    public static final String OAUTH = V1 + "/oauth";

    private ApiPaths() {
    }
}
