package com.superagent.core.tenant;

/**
 * Published once per company when the application is ready, and handled inside that company's
 * {@link TenantContext}: start-up work on company data listens for this instead of the application-ready event,
 * which has no company.
 */
public record CompanyStarted(String companyId) {
}
