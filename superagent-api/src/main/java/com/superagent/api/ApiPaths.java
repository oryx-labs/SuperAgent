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

    /** The signed-in user's SuperAgent: its inbox and its plans. */
    public static final String SUPER_AGENT = V1 + "/superagent";

    public static final String CONNECTORS = V1 + "/connectors";

    public static final String TASKS = V1 + "/tasks";

    /** Work files: what agents produced (documents, images, videos). */
    public static final String FILES = V1 + "/files";
    public static final String PROJECTS = V1 + "/projects";
    public static final String FILE_FOLDERS = FILES + "/folders";

    /** Saved prompts that run as tasks by schedule or API call. */
    public static final String AUTOMATIONS = V1 + "/automations";

    public static final String AUTH = V1 + "/auth";
    /** The signed-in user's own account (avatar, password). */
    public static final String ACCOUNT = V1 + "/account";

    /** Registering an organization (open to anyone, before signing in). */
    public static final String ORGS = V1 + "/orgs";
    /** Anyone's avatar, for any signed-in user. */
    public static final String AVATARS = V1 + "/avatars";

    public static final String USERS = V1 + "/users";

    /** Platform-wide figures for administrators. */
    public static final String ANALYTICS = V1 + "/admin/analytics";
    /** The caller's own token usage. */
    public static final String USAGE = V1 + "/usage";
    /** Everyone's token usage (admins). */
    public static final String ADMIN_USAGE = V1 + "/admin/usage";

    /** The organisation: managers and teams (admin). */
    public static final String ORG = V1 + "/admin/org";
    /** Where Files keeps content: the store new files go to, and each store's settings (admin). */
    public static final String FILE_STORAGE = V1 + "/admin/file-storage";
    /** What each model that can run costs (admins). */
    public static final String ADMIN_MODEL_PRICES = V1 + "/admin/model-prices";

    /** Model-vendor CLIs (claude, codex, ...) the runtime can call. */
    public static final String PROVIDERS = V1 + "/providers";

    /** Platform-wide default configuration (e.g. the Agent chat model). */
    public static final String SETTINGS = V1 + "/settings";
    /** Company-wide settings for outside systems (GitHub, Feishu); administrators only. */
    public static final String INTEGRATIONS = V1 + "/admin/integrations";
    /** The company logo; public, since the sign-in page shows it. */
    public static final String BRANDING_LOGO = V1 + "/branding/logo";

    /** OAuth authorize/callback endpoints for connectors that use a provider handshake. */
    public static final String OAUTH = V1 + "/oauth";


    /** Shared skills (working standards) that agents and tasks follow. */
    public static final String SKILLS = V1 + "/skills";

    /** Messages received on the user's behalf (their Feishu bot, ...), triage, and extracted to-dos. */
    public static final String INBOX = V1 + "/inbox";

    /** The signed-in user's to-do home page: decisions, actions, finished work, heads-ups. */
    public static final String NOTICES = V1 + "/notices";

    private ApiPaths() {
    }
}
