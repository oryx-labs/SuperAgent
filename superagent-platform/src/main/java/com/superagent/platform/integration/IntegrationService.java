package com.superagent.platform.integration;

import com.superagent.api.integration.IntegrationDto;
import com.superagent.api.integration.SaveIntegrationRequest;
import com.superagent.core.error.NotFoundException;
import com.superagent.core.integration.IntegrationChanged;
import com.superagent.core.security.SecretCipher;
import com.superagent.core.settings.SystemSettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Company-wide settings for the outside systems SuperAgent links to: the GitHub and Gitee OAuth apps employees
 * authorize through, the company's Feishu app. Administrators manage them under System Settings; nothing about them lives
 * in the configuration file. Each system declares its fields here, so adding one is a new {@link Definition}.
 *
 * <p>Values sit in the system settings as {@code integration.<key>.<field>}; secrets are encrypted with
 * {@link SecretCipher} and never leave this service except to the code that uses them.
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    public static final String GITHUB = "github";
    public static final String FEISHU = "feishu";
    public static final String GITEE = "gitee";
    public static final String DINGTALK = "dingtalk";
    public static final String NOTION = "notion";
    /** The company's Confluence site: no app to make, only where it is (self-hosted or Cloud). */
    public static final String CONFLUENCE = "confluence";

    /**
     * {@code options}: the values it can take (a choice, shown as a select), else free text; {@code defaultValue}
     * makes it optional, and stands in while it's unset.
     */
    public record FieldDef(String key, String label, boolean secret, List<IntegrationDto.Option> options, String defaultValue) {

        public FieldDef(String key, String label, boolean secret) {
            this(key, label, secret, List.of(), null);
        }
    }

    /** Feishu and Lark are one platform under two domains; the app is made on one of them. */
    public static final String FEISHU_DOMAIN_FEISHU = "feishu";
    public static final String FEISHU_DOMAIN_LARK = "lark";

    /** {@code callbackPath}: the path the provider redirects back to, or null when it has none. */
    public record Definition(String key, String name, String callbackPath, List<FieldDef> fields) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(GITHUB, "GitHub", "/api/v1/oauth/github/callback", List.of(
                    new FieldDef("clientId", "Client ID", false),
                    new FieldDef("clientSecret", "Client Secret", true))),
            new Definition(GITEE, "Gitee（码云）", "/api/v1/oauth/gitee/callback", List.of(
                    new FieldDef("clientId", "Client ID", false),
                    new FieldDef("clientSecret", "Client Secret", true))),
            new Definition(FEISHU, "飞书 / Lark", "/api/v1/oauth/feishu-docs/callback", List.of(
                    new FieldDef("domain", "版本", false, List.of(
                            new IntegrationDto.Option(FEISHU_DOMAIN_FEISHU, "飞书（中国版，open.feishu.cn）"),
                            new IntegrationDto.Option(FEISHU_DOMAIN_LARK, "Lark（国际版，open.larksuite.com）")), FEISHU_DOMAIN_FEISHU),
                    new FieldDef("appId", "App ID", false),
                    new FieldDef("appSecret", "App Secret", true))),
            new Definition(DINGTALK, "钉钉", null, List.of(
                    new FieldDef("clientId", "Client ID（AppKey）", false),
                    new FieldDef("clientSecret", "Client Secret（AppSecret）", true))),
            new Definition(NOTION, "Notion", "/api/v1/oauth/notion/callback", List.of(
                    new FieldDef("clientId", "OAuth Client ID", false),
                    new FieldDef("clientSecret", "OAuth Client Secret", true))),
            new Definition(CONFLUENCE, "Confluence", null, List.of(
                    new FieldDef("url", "站点地址", false))));

    /** Live connection state reported by whatever runs an integration (Feishu's long connection); not stored. */
    private record Status(String state, String error) {
    }

    private final SystemSettingStore store;
    private final SecretCipher cipher;
    private final ApplicationEventPublisher publisher;
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();

    public IntegrationService(SystemSettingStore store, SecretCipher cipher, ApplicationEventPublisher publisher) {
        this.store = store;
        this.cipher = cipher;
        this.publisher = publisher;
    }

    /** Every integration, for administrators; {@code baseUrl} is how this server is reached, for callback URLs. */
    public List<IntegrationDto> list(String baseUrl) {
        Map<String, String> all = store.all();
        return DEFINITIONS.stream().map(d -> toDto(d, all, baseUrl)).toList();
    }

    public IntegrationDto update(String key, SaveIntegrationRequest request, String baseUrl) {
        Definition d = definition(key);
        Map<String, String> values = request.values() == null ? Map.of() : request.values();
        Map<String, String> all = store.all();
        Map<String, String> next = new LinkedHashMap<>();
        for (FieldDef f : d.fields()) {
            String raw = blank(values.get(f.key()));
            String given = raw != null && CONFLUENCE.equals(key) && "url".equals(f.key()) ? siteUrl(raw) : raw;
            if (f.secret()) {
                // A blank secret keeps the stored one: the browser never has it to send back.
                next.put(f.key(), given != null ? cipher.encrypt(given) : blank(all.get(name(key, f.key()))));
            } else if (!f.options().isEmpty() && given != null
                    && f.options().stream().noneMatch(o -> o.value().equals(given))) {
                throw new IllegalArgumentException(f.label() + " must be one of "
                        + f.options().stream().map(IntegrationDto.Option::value).toList());
            } else {
                next.put(f.key(), given);
            }
        }
        boolean enabled = request.enabled() != null ? request.enabled() : "true".equals(all.get(name(key, "enabled")));
        if (enabled) {
            for (FieldDef f : d.fields()) {
                if (next.get(f.key()) == null && f.defaultValue() == null) {
                    throw new IllegalArgumentException(f.label() + " is required to enable " + d.name());
                }
            }
        }
        next.forEach((field, value) -> store.put(name(key, field), value));
        store.put(name(key, "enabled"), enabled ? "true" : "false");
        if (!enabled) {
            statuses.remove(key);
        }
        log.info("integration {} saved ({})", key, enabled ? "enabled" : "disabled");
        publisher.publishEvent(new IntegrationChanged(key));
        return toDto(d, store.all(), baseUrl);
    }

    // --- for the code that uses an integration ---

    /** Enabled, with every field filled in. */
    public boolean ready(String key) {
        Map<String, String> all = store.all();
        Definition d = definition(key);
        return "true".equals(all.get(name(key, "enabled")))
                && d.fields().stream().allMatch(f -> f.defaultValue() != null || blank(all.get(name(key, f.key()))) != null);
    }

    /** A field's value (secrets decrypted) when the integration is ready, else empty. */
    public Optional<String> value(String key, String field) {
        if (!ready(key)) {
            return Optional.empty();
        }
        Definition d = definition(key);
        String raw = store.all().get(name(key, field));
        FieldDef def = d.fields().stream().filter(f -> f.key().equals(field)).findFirst().orElse(null);
        if (def == null || !def.secret()) {
            String v = blank(raw);
            return Optional.ofNullable(v != null ? v : def == null ? null : def.defaultValue());
        }
        try {
            return Optional.of(cipher.decrypt(raw));
        } catch (IllegalStateException e) {
            log.warn("integration {}: stored {} can't be decrypted; re-enter it in System Settings", key, field);
            return Optional.empty();
        }
    }

    public void setStatus(String key, String state, String error) {
        statuses.put(key, new Status(state, error));
    }

    /** connecting | connected | error, or null when nothing has reported. */
    public String status(String key) {
        Status s = statuses.get(key);
        return s == null ? null : s.state();
    }

    public String lastError(String key) {
        Status s = statuses.get(key);
        return s == null ? null : s.error();
    }

    /**
     * Where {@code key}'s OAuth sign-in comes back to. An integration names its own; a sign-in that rides on another
     * integration's app (Feishu docs on the Feishu app) uses the standard path for its key.
     */
    public static String callbackUrl(String key, String baseUrl) {
        String path = DEFINITIONS.stream().filter(d -> d.key().equals(key)).map(Definition::callbackPath)
                .findFirst().orElse(DEFINITIONS.stream().anyMatch(d -> d.key().equals(key)) ? null : OAUTH_PATH + key + "/callback");
        return path == null ? null : trimSlash(baseUrl) + path;
    }

    private static final String OAUTH_PATH = "/api/v1/oauth/";

    // --- helpers ---

    private IntegrationDto toDto(Definition d, Map<String, String> all, String baseUrl) {
        List<IntegrationDto.Field> fields = d.fields().stream().map(f -> {
            String raw = blank(all.get(name(d.key(), f.key())));
            String shown = raw != null ? raw : f.defaultValue();
            return new IntegrationDto.Field(f.key(), f.label(), f.secret(), f.secret() ? null : shown, shown != null, f.options());
        }).toList();
        boolean enabled = "true".equals(all.get(name(d.key(), "enabled")));
        boolean configured = fields.stream().allMatch(IntegrationDto.Field::set);
        return new IntegrationDto(d.key(), d.name(), enabled, configured,
                d.callbackPath() == null ? null : trimSlash(baseUrl) + d.callbackPath(),
                enabled ? status(d.key()) : null, enabled ? lastError(d.key()) : null, fields);
    }

    private static Definition definition(String key) {
        return DEFINITIONS.stream().filter(d -> d.key().equals(key)).findFirst()
                .orElseThrow(() -> new NotFoundException("No such integration: " + key));
    }

    private static String name(String key, String field) {
        return "integration." + key + "." + field;
    }

    /** A site address: http(s), without the trailing slash. */
    private static String siteUrl(String url) {
        if (!url.matches("(?i)https?://[^\\s/]+(/\\S*)?")) {
            throw new IllegalArgumentException("站点地址要以 http:// 或 https:// 开头，例如 https://wiki.example.com");
        }
        return trimSlash(url);
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.strip();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
