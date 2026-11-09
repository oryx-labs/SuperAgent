package com.superagent.platform.user;

import com.superagent.api.user.SaveUserRequest;
import com.superagent.api.user.UserDto;
import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import com.superagent.platform.tenant.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-facing user provisioning: this is the "入职/授权" list the preprovision-only
 * auth checks against. Passwords are BCrypt-hashed and never returned.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserStore users;
    private final PasswordEncoder encoder;
    private final QuotaService quotas;

    public UserService(UserStore users, PasswordEncoder encoder, QuotaService quotas) {
        this.quotas = quotas;
        this.users = users;
        this.encoder = encoder;
    }

    /** Everyone in the company who can sign in. */
    public List<UserDto> list() {
        return users.listUsers().stream().map(UserService::toDto).toList();
    }

    public UserDto create(SaveUserRequest request) {
        String username = clean(request.username());
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username is required");
        }
        // Unique within the company (sign-in names the organization too).
        if (users.findByUsername(username).isPresent()) {
            throw new UsernameTakenException(username);
        }
        quotas.checkCanAddUser(users.listUsers().size());
        UserSource source = parseSource(request.source());
        if (source == UserSource.PHONE && blankToNull(request.phone()) == null) {
            throw new IllegalArgumentException("phone is required");
        }
        Instant now = Instant.now();
        User user = new User(
                UUID.randomUUID().toString(),
                username,
                blankToNull(request.displayName()),
                roles(request.roles()),
                request.enabled() == null || request.enabled(),
                hashIfPresent(request.password(), source),
                blankToNull(request.phone()),
                blankToNull(request.externalId()),
                source, now, now, request.password() != null && !request.password().isBlank());
        users.save(user); // a password the admin set: the user replaces it at first sign-in
        log.info("provisioned user {} ({}, {})", username, source, user.roles());
        return toDto(user);
    }

    public UserDto update(String id, SaveUserRequest request) {
        User existing = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        String username = clean(request.username());
        if (username.isEmpty()) {
            username = existing.username();
        } else if (!username.equals(existing.username())
                && users.findByUsername(username).isPresent()) {
            throw new UsernameTakenException(username);
        }
        UserSource source = request.source() == null || request.source().isBlank()
                ? existing.source() : parseSource(request.source());
        boolean resetPassword = request.password() != null && !request.password().isBlank();
        String passwordHash = resetPassword ? encoder.encode(request.password()) : existing.passwordHash();
        User updated = new User(
                existing.id(), username,
                request.displayName() != null ? blankToNull(request.displayName()) : existing.displayName(),
                request.roles() != null ? roles(request.roles()) : existing.roles(),
                request.enabled() == null ? existing.enabled() : request.enabled(),
                passwordHash,
                request.phone() != null ? blankToNull(request.phone()) : existing.phone(),
                request.externalId() != null ? blankToNull(request.externalId()) : existing.externalId(),
                source, existing.createdAt(), Instant.now(), resetPassword || existing.mustChangePassword());
        requireAnAdminLeft(existing, updated);
        users.save(updated);
        return toDto(updated);
    }

    public void delete(String id) {
        User existing = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        requireAnAdminLeft(existing, null);
        users.delete(id); // its id is never given to anyone else, so nothing it owned passes to a newcomer
    }

    /** A company always keeps an enabled admin: otherwise no one could manage it again. */
    private void requireAnAdminLeft(User before, User after) {
        boolean wasAdmin = before.enabled() && before.isAdmin();
        boolean stillAdmin = after != null && after.enabled() && after.isAdmin();
        if (wasAdmin && !stillAdmin && users.listUsers().stream()
                .noneMatch(u -> !u.id().equals(before.id()) && u.enabled() && u.isAdmin())) {
            throw new IllegalArgumentException("这是公司唯一的管理员，不能删除、停用或取消管理员角色");
        }
    }

    private String hashIfPresent(String password, UserSource source) {
        if (password == null || password.isBlank()) {
            return null;
        }
        return encoder.encode(password);
    }

    private static Set<String> roles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of("USER");
        }
        Set<String> out = new LinkedHashSet<>();
        roles.forEach(r -> { if (r != null && !r.isBlank()) out.add(r.trim().toUpperCase()); });
        if (out.isEmpty()) {
            out.add("USER");
        }
        return out;
    }

    private static UserSource parseSource(String value) {
        return value == null || value.isBlank() ? UserSource.LOCAL : UserSource.valueOf(value.trim().toUpperCase());
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UserDto toDto(User u) {
        return new UserDto(u.id(), u.username(), u.displayName(), List.copyOf(u.roles()), u.enabled(),
                u.phone(), u.externalId(), u.source().name().toLowerCase(), u.createdAt());
    }
}
