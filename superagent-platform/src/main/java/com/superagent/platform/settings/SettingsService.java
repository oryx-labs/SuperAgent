package com.superagent.platform.settings;

import com.superagent.api.settings.SystemSettingsDto;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.provider.ProviderService;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Reads and writes the platform-wide defaults that apply to every user. */
@Service
public class SettingsService {

    static final String AGENT_DEFAULT_MODEL = "agent.default-model";
    static final String COMPANY_NAME = "company.name";
    static final String COMPANY_SHORT_NAME = "company.short-name";
    static final String PUBLIC_URL = "app.public-url";
    static final String BACKUP_MODELS = "agent.backup-models";

    private final SystemSettingStore store;
    private final ProviderService providerService;

    public SettingsService(SystemSettingStore store, ProviderService providerService) {
        this.store = store;
        this.providerService = providerService;
    }

    public SystemSettingsDto get() {
        Map<String, String> all = store.all();
        return new SystemSettingsDto(trimToNull(all.get(AGENT_DEFAULT_MODEL)), trimToNull(all.get(COMPANY_NAME)),
                trimToNull(all.get(COMPANY_SHORT_NAME)), trimToNull(all.get(PUBLIC_URL)), backupModels(all));
    }

    /**
     * Only a model the background probe saw answer, under any managed CLI, may become the default:
     * being listed isn't enough. Chat picks the CLI from the model at run time.
     */
    public SystemSettingsDto update(SystemSettingsDto request) {
        String model = trimToNull(request.agentDefaultModel());
        // Only a newly chosen model is checked: one that has since failed mustn't block saving the other settings.
        if (model != null && !model.equals(agentDefaultModel()) && providerService.cliFor(model).isEmpty()) {
            throw new IllegalArgumentException("Model " + model + " has not passed the inference check");
        }
        store.put(AGENT_DEFAULT_MODEL, model);
        store.put(COMPANY_NAME, name(request.companyName(), 100));
        store.put(COMPANY_SHORT_NAME, name(request.companyShortName(), 20));
        if (request.backupModels() != null) {
            java.util.List<String> backups = new java.util.ArrayList<>();
            for (String m : request.backupModels()) {
                String id = trimToNull(m);
                if (id == null || backups.contains(id) || id.equals(model)) {
                    continue;
                }
                // Like the default: only a model the probe saw answer, unless it was already saved.
                if (!backupModels().contains(id) && providerService.cliFor(id).isEmpty()) {
                    throw new IllegalArgumentException("Model " + id + " has not passed the inference check");
                }
                backups.add(id);
            }
            store.put(BACKUP_MODELS, backups.isEmpty() ? null : String.join(",", backups));
        }
        if (request.publicUrl() != null) {
            store.put(PUBLIC_URL, publicUrl(request.publicUrl()));
        }
        return get();
    }

    /** The model conversations run on (and that judges each task's model), or null for the CLI's own default. */
    public String agentDefaultModel() {
        return get().agentDefaultModel();
    }

    /** Where conversations go, in order, when the default model can't run. */
    public java.util.List<String> backupModels() {
        return backupModels(store.all());
    }

    private static java.util.List<String> backupModels(Map<String, String> all) {
        String v = trimToNull(all.get(BACKUP_MODELS));
        return v == null ? java.util.List.of() : java.util.Arrays.stream(v.split(",")).map(String::strip)
                .filter(x -> !x.isEmpty()).toList();
    }

    /** How people reach this server from outside, set by the admin; null when it should follow the request. */
    public String publicUrl() {
        return trimToNull(store.all().get(PUBLIC_URL));
    }

    /** Scheme and host required (a path is fine, for a server under a sub-path); no query, no trailing slash. */
    private static String publicUrl(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return null;
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(v);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("公网访问地址格式不对：" + v);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("公网访问地址要以 http:// 或 https:// 开头，例如 https://ai.company.com");
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String name(String value, int max) {
        String v = trimToNull(value);
        if (v != null && v.length() > max) {
            throw new IllegalArgumentException("Company name is longer than " + max + " characters");
        }
        return v;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
