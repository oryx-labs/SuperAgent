package com.superagent.api.file;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * File storage for the admin page. {@code active} is the channel new files go to, {@code activeType} its kind.
 * {@code providers}: every installed kind with its settings, filled in from its most recent channel (secrets are
 * never sent back, only whether one is {@code set}). {@code channels}: every place content has been kept — a kind
 * plus a fixed location; files remember theirs, so earlier channels stay readable.
 */
public record FileStorageDto(String active, String activeType, List<Provider> providers, List<Channel> channels) {

    public record Provider(String type, String label, List<Field> fields) {
    }

    public record Field(String key, String label, boolean secret, boolean required, String placeholder, String value,
                        boolean set, boolean locates) {
    }

    /** {@code location}: the settings that say where (endpoint, bucket, prefix…); {@code files}: live files in it. */
    public record Channel(String id, String type, Map<String, String> location, boolean active, long files,
                          Instant createdAt) {
    }
}
