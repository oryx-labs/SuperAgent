package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class SystemSettingStoreTest {

    @Test
    void eachCompanyHasItsOwnValueForAKey() {
        MysqlSystemSettingStore store = new MysqlSystemSettingStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:setting-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.put("company.name", "Acme");
        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.all()).isEmpty();
            store.put("company.name", "Beta");
            assertThat(store.all()).isEqualTo(Map.of("company.name", "Beta"));
        });
        store.put("company.name", "Acme Inc");
        assertThat(store.all()).isEqualTo(Map.of("company.name", "Acme Inc"));
    }
}
