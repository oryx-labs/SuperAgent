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
    static final String CODE_ROOTS = "task.coding.local-roots";
    static final String REVIEW_MAX_ROUNDS = "task.coding.review-max-rounds";
    static final String REVIEW_MAX_SPEND = "task.coding.review-max-spend";
    public static final int DEFAULT_REVIEW_MAX_ROUNDS = 8;
    public static final double DEFAULT_REVIEW_MAX_SPEND = 30;
    /** Matches {@code CodeBuddyCliAgentRunner.INTERNET_ENV_SETTING}. */
    static final String CODEBUDDY_INTERNET_ENV = "codebuddy.internet-environment";
    public static final int DEFAULT_MAX_SUBTASKS = 10;
    public static final int DEFAULT_MAX_PARALLEL = 5;
    private static final java.util.Set<String> CODEBUDDY_INTERNET_ENVS =
            java.util.Set.of("internal", "public", "ioa", "cloudhosted", "selfhosted");

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
                "true".equals(all.get(TASK_FIXED_ON)), list(all, TASK_FIXED_MODELS), lines(all, CODE_ROOTS),
                number(all, REVIEW_MAX_ROUNDS, DEFAULT_REVIEW_MAX_ROUNDS), decimal(all, REVIEW_MAX_SPEND, DEFAULT_REVIEW_MAX_SPEND), codebuddyInternetEnvironment(all));
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
        java.util.List<String> backups = request.backupModels() == null ? null : models(request.backupModels(), backupModels());
        String url = request.publicUrl() == null ? null : publicUrl(request.publicUrl());
        String roots = request.codeRoots() == null ? null : codeRoots(request.codeRoots());
        Integer subtasks = request.complexMaxSubtasks() == null ? null : bounded(request.complexMaxSubtasks(), 1, 50, "子任务上限");
        Integer parallel = request.complexMaxParallel() == null ? null
                : bounded(request.complexMaxParallel(), 1, 20, "同时运行的子任务数");
        Integer rounds = request.codingReviewMaxRounds() == null ? null
                : bounded(request.codingReviewMaxRounds(), 1, 20, "编码任务审查的最多轮数");
        Double spend = request.codingReviewMaxSpend() == null ? null : spend(request.codingReviewMaxSpend());
        String companyName = name(request.companyName(), 100);
        String shortName = name(request.companyShortName(), 20);

        String codebuddyEnv = request.codebuddyInternetEnvironment() == null ? null
                : requireCodebuddyInternetEnvironment(request.codebuddyInternetEnvironment());

        store.put(AGENT_DEFAULT_MODEL, model);
        store.put(COMPANY_NAME, companyName);
        store.put(COMPANY_SHORT_NAME, shortName);
        if (backups != null) {
            store.put(BACKUP_MODELS, join(backups));
        }
        store.put(TASK_FIXED_MODELS, join(fixed));
        store.put(TASK_FIXED_ON, fixedOn ? "true" : null);
        if (request.publicUrl() != null) {
            store.put(PUBLIC_URL, url);
        }
        if (request.codeRoots() != null) {
            store.put(CODE_ROOTS, roots);
        }
        if (subtasks != null) {
            store.put(COMPLEX_MAX_SUBTASKS, String.valueOf(subtasks));
        }
        if (parallel != null) {
            store.put(COMPLEX_MAX_PARALLEL, String.valueOf(parallel));
        }
        if (rounds != null) {
            store.put(REVIEW_MAX_ROUNDS, String.valueOf(rounds));
        }
        if (spend != null) {
            store.put(REVIEW_MAX_SPEND, String.valueOf(spend));
        }
        if (request.codebuddyInternetEnvironment() != null) {
            store.put(CODEBUDDY_INTERNET_ENV, codebuddyEnv);
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

    /** CodeBuddy region for this company; unset → China ({@code internal}). */
    public String codebuddyInternetEnvironment() {
        return codebuddyInternetEnvironment(store.all());
    }

    private static String codebuddyInternetEnvironment(Map<String, String> all) {
        String v = trimToNull(all.get(CODEBUDDY_INTERNET_ENV));
        if (v == null) {
            return "internal";
        }
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        return CODEBUDDY_INTERNET_ENVS.contains(lower) ? lower : "internal";
    }

    private static String requireCodebuddyInternetEnvironment(String value) {
        String v = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!CODEBUDDY_INTERNET_ENVS.contains(v)) {
            throw new IllegalArgumentException("CodeBuddy 网络环境只能是 internal / public / ioa / cloudhosted / selfhosted");
        }
        return v;
    }

    /** The most subtasks a complex task may hand out. */
    public int complexMaxSubtasks() {
        return number(store.all(), COMPLEX_MAX_SUBTASKS, DEFAULT_MAX_SUBTASKS);
    }

    /** How many of a complex task's subtasks run at once. */
    public int complexMaxParallel() {
        return number(store.all(), COMPLEX_MAX_PARALLEL, DEFAULT_MAX_PARALLEL);
    }

    /** A coding task's review loop stops for the user after this many rounds. */
    public int codingReviewMaxRounds() {
        return number(store.all(), REVIEW_MAX_ROUNDS, DEFAULT_REVIEW_MAX_ROUNDS);
    }

    /** …or once the task has spent this much (USD). */
    public double codingReviewMaxSpend() {
        return decimal(store.all(), REVIEW_MAX_SPEND, DEFAULT_REVIEW_MAX_SPEND);
    }

    private static double decimal(Map<String, String> all, String key, double fallback) {
        try {
            String v = trimToNull(all.get(key));
            return v == null ? fallback : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double spend(double value) {
        if (!(value >= 1 && value <= 1000)) {
            throw new IllegalArgumentException("编码任务的费用上限要在 1 到 1000 美元之间");
        }
        return value;
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

    /** Directories a coding task may use a local repository under; empty = local projects are off. */
    public java.util.List<String> codeRoots() {
        return lines(store.all(), CODE_ROOTS);
    }

    // One per line: a path may hold a comma.
    private static java.util.List<String> lines(Map<String, String> all, String key) {
        String v = trimToNull(all.get(key));
        return v == null ? java.util.List.of() : v.lines().map(String::strip).filter(x -> !x.isEmpty()).toList();
    }

    /** Each an existing directory, saved as its real path (no links, no ..), so a check against it can't be walked around. */
    private static String codeRoots(java.util.List<String> requested) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String r : requested) {
            String v = trimToNull(r);
            if (v == null) {
                continue;
            }
            java.nio.file.Path p = java.nio.file.Path.of(v);
            if (!p.isAbsolute()) {
                throw new IllegalArgumentException("代码目录要写完整路径：" + v);
            }
            String real;
            try {
                real = p.toRealPath().toString();
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("这台服务器上没有目录 " + v);
            }
            if (!java.nio.file.Files.isDirectory(p) || real.equals(p.getRoot().toString())) {
                throw new IllegalArgumentException("代码目录要是一个具体的目录（不能是根目录）：" + v);
            }
            if (!out.contains(real)) {
                out.add(real);
            }
        }
        return out.isEmpty() ? null : String.join("\n", out);
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
