package com.superagent.core.provider;

import java.util.Locale;

/** The three levels people choose from when the admin has turned tiers on; which models they mean is worked out. */
public enum ModelTier {
    FAST, STANDARD, STRONG;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Null or blank is null (not chosen); anything else that isn't a tier is the caller's mistake. */
    public static ModelTier of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown model tier: " + value);
        }
    }
}
