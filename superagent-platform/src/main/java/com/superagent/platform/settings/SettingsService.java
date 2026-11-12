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
    static final String COMPLEX_MAX_SUBTASKS = "task.complex.max-subtasks";
    static final String COMPLEX_MAX_PARALLEL = "task.complex.max-parallel";
    static final String TASK_FIXED_ON = "task.fixed-models.on";
    static final String TASK_FIXED_MODELS = "task.fixed-models";
    public static final int DEFAULT_MAX_SUBTASKS = 10;
    public static final int DEFAULT_MAX_PARALLEL = 5;

    private final SystemSettingStore store;
    private final ProviderService providerService;

    public SettingsService(SystemSettingStore store, ProviderService providerService) {
        this.store = store;
        this.providerService = providerService;
    }

    private com.superagent.core.tenant.CompanyStore companies; // null in tests: no fallback name

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCompanies(com.superagent.core.tenant.CompanyStore companies) {
        this.companies = companies;
    }

    /** The company's name: as an admin set it here, else the name it was registered with. */
    private String companyName(Map<String, String> all) {
        String set = trimToNull(all.get(COMPANY_NAME));
        if (set != null || companies == null) {
            return set;
        }
        return companies.find(com.superagent.core.tenant.TenantContext.require())
                .map(com.superagent.core.tenant.Company::name).map(SettingsService::trimToNull).orElse(null);
    }

    public SystemSettingsDto get() {
        Map<String, String> all = store.all();
        return new SystemSettingsDto(trimToNull(all.get(AGENT_DEFAULT_MODEL)), companyName(all),
                trimToNull(all.get(COMPANY_SHORT_NAME)), trimToNull(all.get(PUBLIC_URL)), list(all, BACKUP_MODELS),
                number(all, COMPLEX_MAX_SUBTASKS, DEFAULT_MAX_SUBTASKS), number(all, COMPLEX_MAX_PARALLEL, DEFAULT_MAX_PARALLEL),
                "true".equals(all.get(TASK_FIXED_ON)), list(all, TASK_FIXED_MODELS));
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
        // Checked before anything is written, so a refused save changes nothing.
        java.util.List<String> fixed = request.taskFixedModels() == null ? savedTaskFixedModels()
                : models(request.taskFixedModels(), savedTaskFixedModels());
        boolean fixedOn = request.taskFixedModelsOn() != null ? request.taskFixedModelsOn()
                : "true".equals(store.all().get(TASK_FIXED_ON));
        if (fixedOn && fixed.isEmpty()) {
            throw new IllegalArgumentException("开启任务固定模型时，至少要选一个模型");
        }
        store.put(AGENT_DEFAULT_MODEL, model);
        store.put(COMPANY_NAME, name(request.companyName(), 100));
        store.put(COMPANY_SHORT_NAME, name(request.companyShortName(), 20));
        if (request.backupModels() != null) {
            store.put(BACKUP_MODELS, join(models(request.backupModels(), backupModels())));
        }
        store.put(TASK_FIXED_MODELS, join(fixed));
        store.put(TASK_FIXED_ON, fixedOn ? "true" : null);
        if (request.publicUrl() != null) {
            store.put(PUBLIC_URL, publicUrl(request.publicUrl()));
        }
        if (request.complexMaxSubtasks() != null) {
            store.put(COMPLEX_MAX_SUBTASKS, String.valueOf(bounded(request.complexMaxSubtasks(), 1, 50, "子任务上限")));
        }
        if (request.complexMaxParallel() != null) {
            store.put(COMPLEX_MAX_PARALLEL, String.valueOf(bounded(request.complexMaxParallel(), 1, 20, "同时运行的子任务数")));
        }
        return get();
    }

    /**
     * Trimmed, without repeats; like the default model, only one the probe saw answer, unless it was already saved
     * (one that has since failed mustn't block saving the rest).
     */
    private java.util.List<String> models(java.util.List<String> requested, java.util.List<String> saved) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String m : requested) {
            String id = trimToNull(m);
            if (id == null || out.contains(id)) {
                continue;
            }
            if (!saved.contains(id) && providerService.cliFor(id).isEmpty()) {
                throw new IllegalArgumentException("Model " + id + " has not passed the inference check");
            }
            out.add(id);
        }
        return out;
    }

    private static String join(java.util.List<String> ids) {
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    /** The most subtasks a complex task may hand out. */
    public int complexMaxSubtasks() {
        return number(store.all(), COMPLEX_MAX_SUBTASKS, DEFAULT_MAX_SUBTASKS);
    }

    /** How many of a complex task's subtasks run at once. */
    public int complexMaxParallel() {
        return number(store.all(), COMPLEX_MAX_PARALLEL, DEFAULT_MAX_PARALLEL);
    }

    private static int number(Map<String, String> all, String key, int fallback) {
        try {
            String v = trimToNull(all.get(key));
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int bounded(int value, int min, int max, String what) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(what + "要在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    /** The model conversations run on (and that judges each task's model), or null for the CLI's own default. */
    public String agentDefaultModel() {
        return get().agentDefaultModel();
    }

    /** Where conversations go, in order, when the default model can't run. */
    public java.util.List<String> backupModels() {
        return list(store.all(), BACKUP_MODELS);
    }

    /**
     * The models every task is held to, or empty when tasks get a model picked for them. Never empty while on (saving
     * refuses that), but what's listed may since have stopped running: the router judges that.
     */
    public java.util.List<String> taskFixedModels() {
        Map<String, String> all = store.all();
        return "true".equals(all.get(TASK_FIXED_ON)) ? list(all, TASK_FIXED_MODELS) : java.util.List.of();
    }

    private java.util.List<String> savedTaskFixedModels() {
        return list(store.all(), TASK_FIXED_MODELS);
    }

    private static java.util.List<String> list(Map<String, String> all, String key) {
        String v = trimToNull(all.get(key));
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
