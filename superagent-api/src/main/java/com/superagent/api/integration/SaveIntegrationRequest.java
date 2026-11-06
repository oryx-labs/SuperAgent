package com.superagent.api.integration;

import java.util.Map;

/** Enable or disable an integration and set its fields; a blank secret keeps the stored one. */
public record SaveIntegrationRequest(Boolean enabled, Map<String, String> values) {
}
