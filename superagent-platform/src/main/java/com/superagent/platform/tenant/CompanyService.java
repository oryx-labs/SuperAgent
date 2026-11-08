package com.superagent.platform.tenant;

import com.superagent.core.error.ForbiddenException;
import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>Usernames are unique across all companies, so signing in with one is enough to know the company. The
 * config-seeded admin belongs to the {@linkplain Company#DEFAULT_ID default company}, which always exists.
 */
@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyStore companies;
    private final UserStore users;
    private final ApplicationEventPublisher events;
    private final String adminUsername;
    private final AtomicInteger turn = new AtomicInteger();

    public CompanyService(CompanyStore companies, UserStore users, ApplicationEventPublisher events,
                          @Value("${superagent.auth.admin.username:admin}") String adminUsername) {
        this.companies = companies;
        this.users = users;
        this.events = events;
        this.adminUsername = adminUsername;
        if (companies.find(Company.DEFAULT_ID).isEmpty()) {
            companies.save(new Company(Company.DEFAULT_ID, "Default", true, null, null, null));
        }
    }

    /** Start-up work on company data runs once per company, as that company (see {@link CompanyStarted}). */
    @EventListener(ApplicationReadyEvent.class)
    void startCompanies() {
        for (String id : enabledIds()) {
            TenantContext.run(id, () -> events.publishEvent(new CompanyStarted(id)));
        }
    }

    /** The company {@code username} belongs to; empty if there is no such user. */
    public Optional<String> companyOf(String username) {
        if (username == null) {
            return Optional.empty();
        }
        if (adminUsername.equals(username)) {
            return Optional.of(Company.DEFAULT_ID);
        }
        return users.companyOfUsername(username);
    }

    /** Whether {@code username} is taken in any company (usernames are global). */
    public boolean usernameTaken(String username) {
        return adminUsername.equals(username) || users.companyOfUsername(username).isPresent();
    }

    /**
     * Installing or upgrading software changes the shared host, which belongs to the company that runs it (the
     * default one) until a separate platform back office does this.
     */
    public void requireHostCompany() {
        if (!Company.DEFAULT_ID.equals(TenantContext.require())) {
            throw new HostOnlyException();
        }
    }

    /** Only the company that runs the host may change the software on it. */
    public static class HostOnlyException extends ForbiddenException {
        HostOnlyException() {
            super("安装和升级命令行工具会改动整台服务器，只有部署这套系统的公司能操作");
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
