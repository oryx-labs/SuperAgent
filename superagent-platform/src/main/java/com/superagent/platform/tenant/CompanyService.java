package com.superagent.platform.tenant;

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
 * all are equal. Each treats the runtime as its own: today they share one machine, later each gets its own.
 */
@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyStore companies;
    private final ApplicationEventPublisher events;
    private final AtomicInteger turn = new AtomicInteger();

    public CompanyService(CompanyStore companies, ApplicationEventPublisher events) {
        this.companies = companies;
        this.events = events;
    }

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
