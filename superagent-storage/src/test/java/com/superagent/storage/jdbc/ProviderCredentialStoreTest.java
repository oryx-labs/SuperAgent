package com.superagent.storage.jdbc;

import com.superagent.core.provider.ProviderCredential;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class ProviderCredentialStoreTest {

    private static ProviderCredential cred(String id, String provider, int priority) {
        Instant now = Instant.now();
        return new ProviderCredential(id, provider, id, null, "v1:cipher", true, priority, true,
                ProviderCredential.UNKNOWN, null, null, now, now,
                ProviderCredential.CONSOLE, "hash-" + id);
    }

    @Test
    void listsAPoolInPriorityOrderAndForgetsDeletedEntries() {
        MysqlProviderCredentialStore store = new MysqlProviderCredentialStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:cred-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.save(cred("backup", "claude", 2));
        store.save(cred("main", "claude", 1));
        store.save(cred("other", "codex", 1));
        store.save(store.find("backup").orElseThrow().withHealth(ProviderCredential.ERROR, "HTTP 429"));

        assertThat(store.list("claude")).extracting(ProviderCredential::id).containsExactly("main", "backup");
        assertThat(store.find("backup").orElseThrow().lastError()).isEqualTo("HTTP 429");
        assertThat(store.find("main").orElseThrow().baseUrl()).isNull();
        store.delete("main");
        assertThat(store.list("claude")).extracting(ProviderCredential::id).containsExactly("backup");
        assertThat(store.find("main")).isEmpty();
        assertThat(store.knownKeyHashes("claude")).containsExactlyInAnyOrder("hash-main", "hash-backup"); // deleted too

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.list("claude")).isEmpty();
            assertThat(store.knownKeyHashes("claude")).isEmpty();
            store.save(cred("backup", "claude", 9));
            store.delete("backup");
        });
        assertThat(store.find("backup").orElseThrow().priority()).isEqualTo(2);
    }
}
