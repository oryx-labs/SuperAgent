package com.superagent.platform.tenant;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Anyone registers an organization and gets its admin; all organizations are equal. */
class OrganizationServiceTest {

    private final Map<String, Company> companies = new LinkedHashMap<>();
    /** Users per company: each store call reads the current one, as the real store does. */
    private final Map<String, Map<String, User>> users = new LinkedHashMap<>();
    private final List<Object> started = new ArrayList<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private final CompanyStore companyStore = new CompanyStore() {
        @Override public List<Company> list() { return List.copyOf(companies.values()); }
        @Override public Optional<Company> find(String id) { return Optional.ofNullable(companies.get(id)); }
        @Override public Company save(Company c) { companies.put(c.id(), c); return c; }
    };

    private Map<String, User> mine() {
        return users.computeIfAbsent(TenantContext.require(), k -> new LinkedHashMap<>());
    }

    private final UserStore userStore = new UserStore() {
        @Override public List<User> listUsers() { return List.copyOf(mine().values()); }
        @Override public Optional<User> findById(String id) { return Optional.ofNullable(mine().get(id)); }
        @Override public Optional<User> findByUsername(String n) {
            return mine().values().stream().filter(u -> u.username().equals(n)).findFirst();
        }
        @Override public Optional<User> findByPhone(String p) { return Optional.empty(); }
        @Override public Optional<User> findByExternalId(String e) { return Optional.empty(); }
        @Override public User save(User u) { mine().put(u.id(), u); return u; }
        @Override public void delete(String id) { mine().remove(id); }
    };

    private final CompanyService companyService = new CompanyService(companyStore, started::add);

    private OrganizationService service() {
        return new OrganizationService(companyService, userStore, encoder);
    }

    @Test
    void anyoneRegistersAnOrganizationAndGetsItsAdminOnce() {
        OrganizationService orgs = service();
        OrganizationService.Registered r = orgs.register("欧科集团", "OKG");

        assertThat(r.org()).isEqualTo("okg");
        assertThat(r.username()).isEqualTo("okg");
        assertThat(r.password()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(companies).containsKey("okg");
        assertThat(started).containsExactly(new CompanyStarted("okg")); // its start-up work runs now, not at restart
        User admin = TenantContext.call("okg", () -> userStore.findByUsername("okg")).orElseThrow();
        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.mustChangePassword()).isTrue();
        assertThat(encoder.matches(r.password(), admin.passwordHash())).isTrue(); // only the hash is kept

        // Both names are required; taken, reserved and malformed English names are refused.
        assertThatThrownBy(() -> orgs.register("另一家", "okg")).isInstanceOf(OrganizationService.ShortNameTakenException.class);
        assertThatThrownBy(() -> orgs.register("x", "admin")).hasMessageContaining("保留");
        assertThatThrownBy(() -> orgs.register("x", "9lives")).hasMessageContaining("小写字母");
        assertThatThrownBy(() -> orgs.register("x", " ")).hasMessageContaining("英文名");
        assertThatThrownBy(() -> orgs.register("", "acme")).hasMessageContaining("组织名称");

        // On a public server the host is named: registering first claims nothing.
        // A server can close registration.
        OrganizationService closed = new OrganizationService(companyService, userStore, encoder, false);
        assertThat(closed.registrationOpen()).isFalse();
        assertThatThrownBy(() -> closed.register("新公司", "newco")).isInstanceOf(OrganizationService.RegistrationClosedException.class);
        assertThat(companies).doesNotContainKey("newco");

    }
}
