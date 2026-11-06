package com.superagent.api.provider;

/** A catalogue entry for the "add provider" dropdown; {@code added} disables already-managed ones. */
/** {@code supported}: tasks run on it; {@code installable}: the Models page can install it. */
public record CatalogEntryDto(String key, String name, boolean supported, boolean installable, boolean preview,
                              boolean added) {
}
