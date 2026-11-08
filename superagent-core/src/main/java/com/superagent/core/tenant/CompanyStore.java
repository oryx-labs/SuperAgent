package com.superagent.core.tenant;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for companies. One of the few global tables: it is not scoped by {@link TenantContext}. */
public interface CompanyStore {

    List<Company> list();

    Optional<Company> find(String id);

    /** Inserts or replaces by id. */
    Company save(Company company);
}
