package com.superagent.platform.tenant;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.usage.UsageRecord;
import com.superagent.core.usage.UsageStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaServiceTest {

    @Test
    void aCompanysOwnLimitsWinOverTheDefaultsAndZeroMeansUnlimited() {
        Map<String, Company> rows = new ConcurrentHashMap<>();
        rows.put("acme", new Company("acme", "Acme", true, null, 2, 0, 1000L, null, null));
        rows.put("beta", new Company("beta", "Beta", true, null, null, null, null, null, null));
        CompanyStore companies = new CompanyStore() {
            @Override public List<Company> list() { return List.copyOf(rows.values()); }
            @Override public Optional<Company> find(String id) { return Optional.ofNullable(rows.get(id)); }
            @Override public Company save(Company c) { rows.put(c.id(), c); return c; }
        };
        List<UsageRecord> used = new ArrayList<>();
        UsageStore usage = new UsageStore() {
            @Override public void save(UsageRecord r) { used.add(r); }
            @Override public List<UsageRecord> forTask(String taskId) { return List.of(); }
            @Override public List<UsageRecord> between(String user, Instant from, Instant to) {
                return used.stream().filter(r -> !r.at().isBefore(from) && r.at().isBefore(to)).toList();
            }
        };
        used.add(record(Instant.now().minus(40, ChronoUnit.DAYS), 5000)); // an earlier month doesn't count
        used.add(record(Instant.now().minusSeconds(5), 1200));
        QuotaService quotas = new QuotaService(companies, usage, 10, 3, 0);

        TenantContext.run("acme", () -> {
            quotas.checkCanAddUser(1);
            assertThatThrownBy(() -> quotas.checkCanAddUser(2)).isInstanceOf(QuotaExceededException.class);
            assertThat(quotas.canStartTask(100)).isTrue(); // its own 0: unlimited, over the default 3
            assertThatThrownBy(quotas::checkTokens).isInstanceOf(QuotaExceededException.class);
        });
        TenantContext.run("beta", () -> {
            quotas.checkCanAddUser(9);
            assertThat(quotas.canStartTask(2)).isTrue();
            assertThat(quotas.canStartTask(3)).isFalse(); // the default
            quotas.checkTokens(); // default 0: unlimited
        });
    }

    private static UsageRecord record(Instant at, long input) {
        return new UsageRecord("u" + at.toEpochMilli(), at, "ada", "chat", null, null, null, "claude", "m", input, 0, 0, 0,
                0, 0, null, null);
    }
}
