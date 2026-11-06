package com.superagent.platform.user;

import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everyone who can sign in, for looking people up (inviting them to an agent, showing their name): the provisioned
 * users plus the built-in administrator, who lives in configuration rather than the user table.
 */
@Service
public class PeopleDirectory {

    private final UserStore users;
    private final String adminUsername;

    public PeopleDirectory(UserStore users, @Value("${superagent.auth.admin.username:admin}") String adminUsername) {
        this.users = users;
        this.adminUsername = adminUsername;
    }

    /** Enabled people, the built-in administrator included. */
    public List<User> enabled() {
        List<User> out = new ArrayList<>();
        builtInAdmin().ifPresent(out::add);
        users.listUsers().stream().filter(User::enabled).forEach(out::add);
        return out;
    }

    public Optional<User> find(String username) {
        if (username == null) {
            return Optional.empty();
        }
        Optional<User> stored = users.findByUsername(username);
        return stored.isPresent() ? stored : builtInAdmin().filter(u -> u.username().equals(username));
    }

    /** The configured administrator, unless the user table has a row of the same name (which then wins). */
    private Optional<User> builtInAdmin() {
        if (adminUsername == null || adminUsername.isBlank() || users.findByUsername(adminUsername).isPresent()) {
            return Optional.empty();
        }
        Instant epoch = Instant.EPOCH;
        return Optional.of(new User("config:" + adminUsername, adminUsername, null, Set.of("ADMIN"), true, null, null,
                null, UserSource.LOCAL, epoch, epoch));
    }
}
