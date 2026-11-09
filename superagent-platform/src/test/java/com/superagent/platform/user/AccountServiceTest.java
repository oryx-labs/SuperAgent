package com.superagent.platform.user;

import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountServiceTest {

    private static final class Users implements UserStore {
        final Map<String, User> rows = new LinkedHashMap<>();
        @Override public List<User> listUsers() { return List.copyOf(rows.values()); }
        @Override public Optional<User> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Optional<User> findByUsername(String name) {
            return rows.values().stream().filter(u -> u.username().equals(name)).findFirst();
        }
        @Override public Optional<User> findByPhone(String phone) { return Optional.empty(); }
        @Override public Optional<User> findByExternalId(String id) { return Optional.empty(); }
        @Override public User save(User u) { rows.put(u.id(), u); return u; }
        @Override public void delete(String id) { }
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    @BeforeEach
    void company() {
        TenantContext.set("acme");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void usersChangeTheirOwnPasswordAndAvatar(@TempDir Path dir) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        Users users = new Users();
        Instant now = Instant.now();
        users.save(new User("1", "ada", "Ada", Set.of("USER"), true, encoder.encode("old-password"), null, null,
                UserSource.LOCAL, now, now, true)); // a password someone else set
        users.save(new User("2", "13800000000", "Bob", Set.of("USER"), true, null, "13800000000", null,
                UserSource.PHONE, now, now));
        AccountService svc = new AccountService(users, encoder, dir.toString());

        // Accounts are addressed by user id, not username.
        assertThat(svc.mustChangePassword("1")).isTrue();
        assertThatThrownBy(() -> svc.changePassword("1", "wrong", "new-password-1")).hasMessageContaining("当前密码");
        assertThatThrownBy(() -> svc.changePassword("1", "old-password", "short")).hasMessageContaining("至少");
        svc.changePassword("1", "old-password", "new-password-1");
        assertThat(encoder.matches("new-password-1", users.rows.get("1").passwordHash())).isTrue();
        assertThat(svc.mustChangePassword("1")).isFalse(); // their own now
        // Phone users have no password to change here.
        assertThat(svc.canChangePassword("2")).isFalse();

        assertThatThrownBy(() -> svc.setAvatar("1", Base64.getEncoder().encodeToString("<svg/>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
        long v = svc.setAvatar("1", Base64.getEncoder().encodeToString(PNG));
        assertThat(svc.avatarVersions()).containsEntry("1", v);
        assertThat(svc.avatar("1")).hasValueSatisfying(img -> assertThat(img.mediaType()).isEqualTo("image/png"));
        svc.removeAvatar("1");
        assertThat(svc.avatarVersion("1")).isNull();
    }
}
