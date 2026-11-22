package com.superagent.storage.jdbc;

import com.superagent.core.proxyconfirm.ConfirmType;
import com.superagent.core.proxyconfirm.ProxyConfirmRecord;
import com.superagent.core.proxyconfirm.ProxyConfirmRule;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class ProxyConfirmStoreTest {

    @Test
    void rulesAndRecordsStayInTheirCompany() {
        MysqlProxyConfirmStore store = new MysqlProxyConfirmStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:proxy-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant t = Instant.now();
        store.saveRule(new ProxyConfirmRule("ada", "", true, Set.of(ConfirmType.TASK_PARAMS), t));
        store.saveRule(new ProxyConfirmRule("ada", "p1", false, Set.of(), t));
        store.saveRecord(new ProxyConfirmRecord("r1", "ada", "", ConfirmType.TASK_PARAMS, "task", "t1", "no open items", false,
                ProxyConfirmRecord.CONFIRMED, t));
        store.saveRecord(new ProxyConfirmRecord("r1", "ada", "", ConfirmType.TASK_PARAMS, "task", "t1", "no open items", false,
                ProxyConfirmRecord.REVOKED, t));

        assertThat(store.findRule("ada", "").orElseThrow().types()).containsExactly(ConfirmType.TASK_PARAMS);
        assertThat(store.findRule("ada", "p1").orElseThrow().enabled()).isFalse();
        assertThat(store.findRecord("r1").orElseThrow().status()).isEqualTo(ProxyConfirmRecord.REVOKED);
        assertThat(store.records("ada", 10)).hasSize(1);
        assertThat(store.records(null, 10)).hasSize(1);

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.findRule("ada", "")).isEmpty();
            assertThat(store.findRecord("r1")).isEmpty();
            assertThat(store.records(null, 10)).isEmpty();
        });
        store.deleteRule("ada", "p1");
        assertThat(store.findRule("ada", "p1")).isEmpty();
    }
}
