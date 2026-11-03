package com.superagent.core.user;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for provisioned users. The active backend is chosen in superagent-storage. */
public interface UserStore {

    List<User> listUsers();

    Optional<User> findById(String id);

    Optional<User> findByUsername(String username);

    Optional<User> findByPhone(String phone);

    Optional<User> findByExternalId(String externalId);

    /** Inserts or replaces the user by id. */
    User save(User user);

    void delete(String id);
}
