package com.superagent.core.settings;

import java.util.Map;

/**
 * Persistence SPI for platform-wide settings that apply to every user (e.g. the default
 * chat model). A flat key → value map so new settings need no schema change; a blank
 * value means "unset". The active backend is chosen in superagent-storage.
 */
public interface SystemSettingStore {

    Map<String, String> all();

    /** Inserts or replaces the value for {@code key}; null is stored as unset. */
    void put(String key, String value);
}
