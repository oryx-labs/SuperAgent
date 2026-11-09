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

/** Everyone in the company who can sign in, for looking people up (inviting them to an agent, showing their name). */
@Service
public class PeopleDirectory {

    private final UserStore users;

    public PeopleDirectory(UserStore users) {
        this.users = users;
    }

    public List<User> enabled() {
        return users.listUsers().stream().filter(User::enabled).toList();
    }

    /** By user id, which is what everything records about a person. */
    public Optional<User> find(String userId) {
        return userId == null ? Optional.empty() : users.findById(userId);
    }

    /** A person named either way: by user id (from a page) or by username (typed, or said to an agent). */
    public Optional<User> resolve(String idOrUsername) {
        if (idOrUsername == null || idOrUsername.isBlank()) {
            return Optional.empty();
        }
        String key = idOrUsername.strip();
        Optional<User> byId = users.findById(key);
        return byId.isPresent() ? byId : users.findByUsername(key);
    }

    /** By the name someone typed (when inviting by username). */
    public Optional<User> findByUsername(String username) {
        return username == null ? Optional.empty() : users.findByUsername(username.strip());
    }
}
