package com.superagent.api.provider;

/** A catalogue entry for the "add provider" dropdown; {@code added} disables already-managed ones. */
public record CatalogEntryDto(String key, String name, boolean supported, boolean added) {
}
