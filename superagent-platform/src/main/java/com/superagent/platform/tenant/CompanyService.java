package com.superagent.platform.tenant;

import com.superagent.core.error.ForbiddenException;
import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The companies using this system, and which one a person or a piece of background work belongs to.
 *
 * <p>A company's id is its organization's short name, typed at sign-in next to the username (usernames are only
 * unique within a company). There is no built-in one: every organization is registered (see OrganizationService), and
 * the first one registered is the host's ({@link #isHost}).
 */
@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyStore companies;
    private final ApplicationEventPublisher events;
    private final AtomicInteger turn = new AtomicInteger();

    /** The organization named to own the host (its English name); blank = the first one ever registered. */
    private final String hostOrg;

    public CompanyService(CompanyStore companies, ApplicationEventPublisher events) {
        this(companies, events, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CompanyService(CompanyStore companies, ApplicationEventPublisher events,
                          @org.springframework.beans.factory.annotation.Value("${superagent.host-org:}") String hostOrg) {
        this.companies = companies;
        this.events = events;
        this.hostOrg = hostOrg == null ? "" : hostOrg.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The company that owns the host: the one named by {@code superagent.host-org} ({@code SUPERAGENT_HOST_ORG}), or,
     * with none named (a local install), the first one ever registered. Only it changes the software on the machine
     * (CLI installs) and may run on the machine's own keys and CLI logins; every other company brings its own keys.
     * Name it on a public server, so a stranger who registers first after a fresh install can't claim the host.
     *
     * <p>Unnamed, it is pinned to that first company for good (companies are never deleted, and the first one stays the first even
     * when disabled): a disabled host means no host, never the next-oldest company, which could be a stranger's on a
     * public server.
     */
    public boolean isHost(String companyId) {
        if (companyId == null) {
            return false;
        }
        if (!hostOrg.isEmpty()) {
            return hostOrg.equals(companyId) && companies.find(companyId).map(Company::enabled).orElse(false);
        }
        Company first = hostCompany;
        if (first == null) {
            first = companies.list().stream().filter(c -> c.createdAt() != null)
                    .min(java.util.Comparator.comparing(Company::createdAt).thenComparing(Company::id)).orElse(null);
            hostCompany = first; // the first one can't change once there is one
        }
        return first != null && first.id().equals(companyId)
                && companies.find(first.id()).map(Company::enabled).orElse(false);
    }

    private volatile Company hostCompany;

    /** Start-up work on company data runs once per company, as that company (see {@link CompanyStarted}). */
    @EventListener(ApplicationReadyEvent.class)
    void startCompanies() {
        for (String id : enabledIds()) {
            TenantContext.run(id, () -> events.publishEvent(new CompanyStarted(id)));
        }
    }

    /** The enabled company with this organization short name. */
    public Optional<String> enabledCompany(String org) {
        return org == null || org.isBlank() ? Optional.empty()
                : companies.find(org.strip().toLowerCase(java.util.Locale.ROOT)).filter(Company::enabled).map(Company::id);
    }

    public boolean exists(String id) {
        return companies.find(id).isPresent();
    }

    /** Saves a new company and runs its start-up work (seeded connectors and skills, its IM connections). */
    public Company create(String id, String name) {
        java.time.Instant now = java.time.Instant.now();
        Company c = companies.save(new Company(id, name, true, null, now, now));
        TenantContext.run(id, () -> events.publishEvent(new CompanyStarted(id)));
        log.info("company {} ({}) created", id, name);
        return c;
    }

    /**
     * Installing or upgrading software changes the shared host, which belongs to the company that runs it (the
     * default one) until a separate platform back office does this.
     */
    public void requireHostCompany() {
        if (!isHost(TenantContext.require())) {
            throw new HostOnlyException();
        }
    }

    /** Only the company that runs the host may change the software on it. */
    public static class HostOnlyException extends ForbiddenException {
        HostOnlyException() {
            super("安装和升级命令行工具会改动整台服务器，只有第一个注册的组织（部署这套系统的组织）能操作");
        }
    }

    public List<String> enabledIds() {
        return companies.list().stream().filter(Company::enabled).map(Company::id).toList();
    }

    /**
     * Runs {@code work} once per enabled company, as that company. For background loops: one company's failure
     * is logged here and doesn't stop the others' turn — this is the loop's central error point.
     */
    public void forEach(Runnable work) {
        List<String> ids = enabledIds();
        // Round-robin the starting company, so no company is always served first when work is scarce.
        int start = ids.isEmpty() ? 0 : Math.floorMod(turn.getAndIncrement(), ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get((start + i) % ids.size());
            try {
                TenantContext.run(id, work);
            } catch (RuntimeException e) {
                log.warn("Background work failed for company {}", id, e);
            }
        }
    }
}
