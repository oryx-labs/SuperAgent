package com.superagent.storage.jdbc;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyStoreTest {

    @Test
    void savesAndReplacesCompanies() {
        JdbcCompanyStore store = new JdbcCompanyStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:company-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                JdbcCompanyStore.MYSQL_DDL);
        Company acme = store.save(new Company("acme", "Acme", true, null, null, null));
        store.save(new Company("beta", "Beta", true, null, null, null));
        store.save(new Company("acme", "Acme Ltd", false, "v1:key", acme.createdAt(), null));

        assertThat(store.list()).extracting(Company::id).containsExactly("acme", "beta");
        assertThat(store.find("acme")).hasValueSatisfying(c -> {
            assertThat(c.name()).isEqualTo("Acme Ltd");
            assertThat(c.enabled()).isFalse();
            assertThat(c.dataKeyCipher()).isEqualTo("v1:key");
        });
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    void aCompanyTableFromBeforeLimitsGetsTheirColumnsAndKeepsItsRows() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:company-old-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE company (id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                + "enabled BOOLEAN NOT NULL, data_key_cipher TEXT, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL)");
        jdbc.update("INSERT INTO company VALUES ('default', 'Default', TRUE, 'v1:key', NOW(), NOW())");

        JdbcCompanyStore store = new JdbcCompanyStore(jdbc, JdbcCompanyStore.MYSQL_DDL);
        assertThat(store.find("default")).hasValueSatisfying(c -> {
            assertThat(c.dataKeyCipher()).isEqualTo("v1:key");
            assertThat(c.maxUsers()).isNull();
        });
    }

    @Test
    void contextFailsClosedAndRestoresTheOuterCompany() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
        TenantContext.run("acme", () -> {
            Runnable later = TenantContext.wrap(() -> assertThat(TenantContext.require()).isEqualTo("acme"));
            TenantContext.run("beta", () -> {
                assertThat(TenantContext.require()).isEqualTo("beta");
                later.run();
            });
            assertThat(TenantContext.require()).isEqualTo("acme");
        });
        assertThat(TenantContext.current()).isEmpty();
    }
}
