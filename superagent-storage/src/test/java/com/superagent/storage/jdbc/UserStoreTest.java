package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(InCompany.class)
class UserStoreTest {

    private static User user(String id, String username, String displayName) {
        Instant now = Instant.now();
        return new User(id, username, displayName, Set.of("USER"), true, null, "138", null, UserSource.LOCAL, now, now);
    }

    @Test
    void usersStayInTheirCompanyAndUsernamesAreGloballyUnique() {
        MysqlUserStore store = new MysqlUserStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:user-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.save(user("u1", "ada", "Ada"));
        store.save(user("u1", "ada", "Ada L.")); // update in place
        assertThat(store.findById("u1")).hasValueSatisfying(u -> assertThat(u.displayName()).isEqualTo("Ada L."));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.listUsers()).isEmpty();
            assertThat(store.findByUsername("ada")).isEmpty();
            assertThat(store.findByPhone("138")).isEmpty();
            store.delete("u1");
            store.save(user("u1", "bob", "Bob")); // same id, other company: a separate row
            assertThatThrownBy(() -> store.save(user("u2", "ada", "Impostor"))).isInstanceOf(DuplicateKeyException.class);
        });
        assertThat(store.listUsers()).singleElement().satisfies(u -> {
            assertThat(u.username()).isEqualTo("ada");
            assertThat(u.displayName()).isEqualTo("Ada L.");
        });

        TenantContext.clear(); // login runs before any company is known
        assertThat(store.companyOfUsername("ada")).contains(InCompany.ID);
        assertThat(store.companyOfUsername("bob")).contains(InCompany.OTHER);
        assertThat(store.companyOfUsername("nobody")).isEmpty();
    }
}
