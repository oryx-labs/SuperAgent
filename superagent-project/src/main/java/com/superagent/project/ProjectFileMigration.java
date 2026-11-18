package com.superagent.project;

import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** On start, mounts files recorded in a project before shared folders existed at that project's root (idempotent). */
@Component
class ProjectFileMigration {

    private static final Logger log = LoggerFactory.getLogger(ProjectFileMigration.class);

    private final CompanyStore companies;
    private final ProjectService projects;

    ProjectFileMigration(CompanyStore companies, ProjectService projects) {
        this.companies = companies;
        this.projects = projects;
    }

    @EventListener(ApplicationReadyEvent.class)
    void migrate() {
        companies.list().forEach(c -> TenantContext.run(c.id(), () -> {
            int n = projects.mountLegacyFiles();
            if (n > 0) {
                log.info("company {}: mounted {} earlier project files at their project's root", c.id(), n);
            }
        }));
    }
}
