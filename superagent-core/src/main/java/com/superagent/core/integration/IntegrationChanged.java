package com.superagent.core.integration;

/** Published when an administrator saves an integration's settings, so whatever runs on them reconnects. */
public record IntegrationChanged(String key) {
}
