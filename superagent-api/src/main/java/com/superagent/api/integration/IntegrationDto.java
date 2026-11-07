package com.superagent.api.integration;

import java.util.List;

/**
 * One outside system's company-wide settings, for administrators. Secret fields never carry their value,
 * only whether one is stored. {@code callbackUrl} is what to register at the provider (null when it has none).
 * {@code status} / {@code lastError} report a live connection (Feishu): connecting | connected | error.
 */
public record IntegrationDto(
        String key,
        String name,
        boolean enabled,
        boolean configured,
        String callbackUrl,
        String status,
        String lastError,
        List<Field> fields) {

    /** {@code options}: the values a choice can take (shown as a select); empty for free text. */
    public record Field(String key, String label, boolean secret, String value, boolean set, List<Option> options) {
    }

    public record Option(String value, String label) {
    }
}
