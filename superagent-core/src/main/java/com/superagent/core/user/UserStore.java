package com.superagent.core.user;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for provisioned users. The active backend is chosen in superagent-storage. */
public interface UserStore {

    List<User> listUsers();

    Optional<User> findById(String id);

    Optional<User> findByUsername(String username);

    /**
     * The company {@code username} belongs to. The only lookup not scoped to the current company: usernames are
     * globally unique, and login uses this to find which company a username is in, so it needs no tenant context.
     */
    Optional<String> companyOfUsername(String username);

    Optional<User> findByPhone(String phone);

    Optional<User> findByExternalId(String externalId);

    /** Inserts or replaces the user by id. */
    User save(User user);

    void delete(String id);
}
