package com.superagent.core.provider;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for managed model-provider CLIs. The active backend is chosen in superagent-storage. */
public interface ModelProviderStore {

    List<ModelProvider> list();

    Optional<ModelProvider> findById(String id);

    Optional<ModelProvider> findByKey(String providerKey);

    /** Inserts or replaces by id. */
    ModelProvider save(ModelProvider provider);

    void delete(String id);
}
