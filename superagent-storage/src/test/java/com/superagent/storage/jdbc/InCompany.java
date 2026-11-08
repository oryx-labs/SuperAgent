package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs each test as company {@link #ID}, as every store call needs a company. Use
 * {@code TenantContext.run(OTHER, …)} inside a test to check another company sees none of it.
 */
public class InCompany implements BeforeEachCallback, AfterEachCallback {

    public static final String ID = "acme";
    public static final String OTHER = "beta";

    @Override
    public void beforeEach(ExtensionContext context) {
        TenantContext.set(ID);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantContext.clear();
    }
}
