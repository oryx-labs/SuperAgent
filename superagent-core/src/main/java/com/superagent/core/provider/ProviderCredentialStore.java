package com.superagent.core.provider;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for the credential pools. */
public interface ProviderCredentialStore {

    /** One CLI's pool, highest priority (lowest number) first. */
    List<ProviderCredential> list(String providerKey);

    Optional<ProviderCredential> find(String id);

    /** Inserts or replaces by id. */
    void save(ProviderCredential credential);

    void delete(String id);
}
