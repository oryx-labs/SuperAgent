package com.superagent.web.user;

import com.superagent.api.user.SaveUserRequest;
import com.superagent.api.user.UserDto;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    public UserService(UserStore users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public List<UserDto> list() {
        return users.listUsers().stream().map(UserService::toDto).toList();
    }

    public UserDto create(SaveUserRequest request) {
        String username = clean(request.username());
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username is required");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new UsernameTakenException(username);
        }
        UserSource source = parseSource(request.source());
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
                source, now, now);
        users.save(user);
        log.info("provisioned user {} ({}, {})", username, source, user.roles());
        return toDto(user);
    }

    public UserDto update(String id, SaveUserRequest request) {
        User existing = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        String username = clean(request.username());
        if (username.isEmpty()) {
            username = existing.username();
        } else if (!username.equals(existing.username()) && users.findByUsername(username).isPresent()) {
            throw new UsernameTakenException(username);
        }
        UserSource source = request.source() == null || request.source().isBlank()
                ? existing.source() : parseSource(request.source());
        String passwordHash = request.password() != null && !request.password().isBlank()
                ? encoder.encode(request.password()) : existing.passwordHash();
        User updated = new User(
                existing.id(), username,
                request.displayName() != null ? blankToNull(request.displayName()) : existing.displayName(),
                request.roles() != null ? roles(request.roles()) : existing.roles(),
                request.enabled() == null ? existing.enabled() : request.enabled(),
                passwordHash,
                request.phone() != null ? blankToNull(request.phone()) : existing.phone(),
                request.externalId() != null ? blankToNull(request.externalId()) : existing.externalId(),
                source, existing.createdAt(), Instant.now());
        users.save(updated);
        return toDto(updated);
    }

    public void delete(String id) {
        users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        users.delete(id);
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
