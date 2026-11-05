package com.superagent.core.inbox;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A user's connection to one message source (v1: their own Feishu bot). One per owner and type.
 *
 * <p>{@code secretCipher} is the app secret encrypted with {@link com.superagent.core.security.SecretCipher};
 * it is never returned to clients. {@code ownerExternalId} is the owner's own id at the source (their
 * Feishu open_id, learned through the bind code), used to spot mentions of them and to message them.
 * {@code secretaryAgentId} is only read once, to remove the secretary agent the inbox used to post to.
 */
public record EventSource(
        String owner,
        String type,
        String appId,
        String secretCipher,
        boolean enabled,
        String ownerExternalId,
        String bindCode,
        String digestTime,
        String timezone,
        String secretaryAgentId,
        LocalDate lastDigestDate,
        Instant lastHourlyAt,
        String status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static final String FEISHU = "feishu";

    public EventSource withStatus(String newStatus, String newError) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, bindCode,
                digestTime, timezone, secretaryAgentId, lastDigestDate, lastHourlyAt, newStatus,
                newError, createdAt, Instant.now());
    }

    public EventSource withBinding(String externalId) {
        return new EventSource(owner, type, appId, secretCipher, enabled, externalId, null,
                digestTime, timezone, secretaryAgentId, lastDigestDate, lastHourlyAt, status,
                lastError, createdAt, Instant.now());
    }

    public EventSource withBindCode(String code) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, code,
                digestTime, timezone, secretaryAgentId, lastDigestDate, lastHourlyAt, status,
                lastError, createdAt, Instant.now());
    }

    public EventSource withRuns(LocalDate digestDate, Instant hourlyAt) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, bindCode,
                digestTime, timezone, secretaryAgentId, digestDate, hourlyAt, status, lastError,
                createdAt, Instant.now());
    }

    public EventSource withSecretary(String agentId) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, bindCode,
                digestTime, timezone, agentId, lastDigestDate, lastHourlyAt, status, lastError,
                createdAt, Instant.now());
    }
}
