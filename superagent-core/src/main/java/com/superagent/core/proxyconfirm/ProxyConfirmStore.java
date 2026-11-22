package com.superagent.core.proxyconfirm;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for proxy-confirm rules and records; every call is for the current company. */
public interface ProxyConfirmStore {

    Optional<ProxyConfirmRule> findRule(String owner, String projectId);

    void saveRule(ProxyConfirmRule rule);

    void deleteRule(String owner, String projectId);

    void saveRecord(ProxyConfirmRecord record);

    Optional<ProxyConfirmRecord> findRecord(String id);

    /** Newest first; {@code owner} null = everyone in the company. */
    List<ProxyConfirmRecord> records(String owner, int limit);
}
