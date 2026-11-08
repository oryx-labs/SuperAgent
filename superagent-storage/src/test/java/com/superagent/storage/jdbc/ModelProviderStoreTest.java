package com.superagent.storage.jdbc;

import com.superagent.core.provider.ModelProvider;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class ModelProviderStoreTest {

    private static ModelProvider provider(String status) {
        Instant now = Instant.now();
        return new ModelProvider("claude", "claude", true, true, "1.0", null, status, now, null, null, null, now, now);
    }

    @Test
    void theSameProviderIsKeptPerCompany() {
        MysqlModelProviderStore store = new MysqlModelProviderStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:provider-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.save(provider("READY"));
        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.list()).isEmpty();
            assertThat(store.findByKey("claude")).isEmpty();
            store.save(provider("MISSING"));
            store.delete("claude");
        });
        assertThat(store.findById("claude")).hasValueSatisfying(p -> assertThat(p.status()).isEqualTo("READY"));
        assertThat(store.list()).hasSize(1);
    }
}
