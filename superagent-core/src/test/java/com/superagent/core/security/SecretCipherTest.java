package com.superagent.core.security;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    @TempDir
    Path tmp;

    @Test
    void eachCompanysSecretsUseItsOwnKeyAndOnlyOpenInsideIt() {
        Companies store = new Companies();
        store.save(new Company("acme", "Acme", true, null, null, null));
        store.save(new Company("beta", "Beta", true, null, null, null));
        String keyFile = tmp.resolve("secret.key").toString();
        SecretCipher cipher = new SecretCipher("", keyFile, store);

        String sealed = TenantContext.call("acme", () -> cipher.encrypt("s3cret"));
        assertThat(sealed).startsWith("v2:acme:");
        assertThat(store.find("acme").orElseThrow().dataKeyCipher()).startsWith("v1:"); // wrapped by the master key
        assertThatThrownBy(() -> TenantContext.call("beta", () -> cipher.decrypt(sealed)))
                .isInstanceOf(IllegalStateException.class);
        // Relabelling the ciphertext as another company's doesn't open it either: it is bound to its company.
        assertThatThrownBy(() -> TenantContext.call("beta", () -> cipher.decrypt(sealed.replace("v2:acme:", "v2:beta:"))))
                .isInstanceOf(IllegalStateException.class);

        SecretCipher restarted = new SecretCipher("", keyFile, store);
        assertThat(TenantContext.call("acme", () -> restarted.decrypt(sealed))).isEqualTo("s3cret");
        String platform = restarted.encrypt("host"); // no company: the master key
        assertThat(platform).startsWith("v1:");
        assertThat(restarted.decrypt(platform)).isEqualTo("host");
    }

    private static final class Companies implements CompanyStore {
        private final Map<String, Company> rows = new ConcurrentHashMap<>();
        @Override public List<Company> list() { return List.copyOf(rows.values()); }
        @Override public Optional<Company> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Company save(Company c) { rows.put(c.id(), c); return c; }
    }
}
