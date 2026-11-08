package com.superagent.platform.tenant;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.usage.UsageRecord;
import com.superagent.core.usage.UsageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each company's limits: how many users, how many tasks running at once, how many model tokens a month. A
 * company's own setting wins over the configured default; 0 means unlimited (the default for all three).
 */
@Service
public class QuotaService {

    /** Token use is summed from the usage records, so it is re-read at most this often per company. */
    private static final Duration TOKENS_FRESH = Duration.ofMinutes(1);

    public record Limits(int maxUsers, int maxRunningTasks, long monthlyTokens) {
    }

    private record Used(YearMonth month, long tokens, Instant at) {
    }

    private final CompanyStore companies;
    private final UsageStore usage;
    private final Limits defaults;
    private final Map<String, Used> used = new ConcurrentHashMap<>();

    public QuotaService(CompanyStore companies, UsageStore usage,
                        @Value("${superagent.tenant.quota.max-users:0}") int maxUsers,
                        @Value("${superagent.tenant.quota.max-running-tasks:0}") int maxRunningTasks,
                        @Value("${superagent.tenant.quota.monthly-tokens:0}") long monthlyTokens) {
        this.companies = companies;
        this.usage = usage;
        this.defaults = new Limits(maxUsers, maxRunningTasks, monthlyTokens);
    }

    /** The current company's limits. */
    public Limits limits() {
        Company c = companies.find(TenantContext.require()).orElse(null);
        if (c == null) {
            return defaults;
        }
        return new Limits(c.maxUsers() != null ? c.maxUsers() : defaults.maxUsers(),
                c.maxRunningTasks() != null ? c.maxRunningTasks() : defaults.maxRunningTasks(),
                c.monthlyTokens() != null ? c.monthlyTokens() : defaults.monthlyTokens());
    }

    /** Before adding a user: {@code users} is how many the company has now. */
    public void checkCanAddUser(int users) {
        int max = limits().maxUsers();
        if (max > 0 && users >= max) {
            throw new QuotaExceededException("公司的账号数已到上限（" + max + " 个）");
        }
    }

    /** Whether another task may start while {@code running} of the company's are running. */
    public boolean canStartTask(int running) {
        int max = limits().maxRunningTasks();
        return max <= 0 || running < max;
    }

    /** Before new model work (a task, a conversation turn): the month's tokens must not be used up. */
    public void checkTokens() {
        long max = limits().monthlyTokens();
        if (max > 0 && tokensThisMonth() >= max) {
            throw new QuotaExceededException("公司本月的模型用量已到上限（" + max + " tokens），下月恢复或请管理员调整额度");
        }
    }

    public long tokensThisMonth() {
        String company = TenantContext.require();
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        Instant now = Instant.now();
        Used u = used.get(company);
        if (u == null || !u.month().equals(month) || u.at().plus(TOKENS_FRESH).isBefore(now)) {
            Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            long total = usage.between(null, from, now).stream().mapToLong(UsageRecord::total).sum();
            u = new Used(month, total, now);
            used.put(company, u);
        }
        return u.tokens();
    }
}
