package com.superagent.core.inbox;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An employee's link to a messaging connector (Feishu): who they are there, and the schedule their inbox runs
 * on. One per owner and type, created when the company app matches them (by phone) or they bind with a code.
 *
 * <p>{@code appId} is the company app the identity belongs to: Feishu ids are per app, so a link made under
 * another app no longer counts ({@link #linkedTo}). {@code ownerExternalId} is the owner's id there (their
 * Feishu open_id), used to route messages to them and to message them. {@code secretaryAgentId} is only read
 * once, to remove the secretary agent the inbox used to post to.
 */
public record EventSource(
        String owner,
        String type,
        String appId,
        boolean enabled,
        String ownerExternalId,
        String bindCode,
        String digestTime,
        String timezone,
        String secretaryAgentId,
        LocalDate lastDigestDate,
        Instant lastHourlyAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String FEISHU = "feishu";

    /** Whether the owner's identity is known under this app. */
    public boolean linkedTo(String currentAppId) {
        return ownerExternalId != null && currentAppId != null && currentAppId.equals(appId);
    }

    public EventSource withBinding(String app, String externalId) {
        return new EventSource(owner, type, app, enabled, externalId, null, digestTime, timezone, secretaryAgentId,
                lastDigestDate, lastHourlyAt, createdAt, Instant.now());
    }

    public EventSource withBindCode(String code) {
        return new EventSource(owner, type, appId, enabled, ownerExternalId, code, digestTime, timezone,
                secretaryAgentId, lastDigestDate, lastHourlyAt, createdAt, Instant.now());
    }

    public EventSource withRuns(LocalDate digestDate, Instant hourlyAt) {
        return new EventSource(owner, type, appId, enabled, ownerExternalId, bindCode, digestTime, timezone,
                secretaryAgentId, digestDate, hourlyAt, createdAt, Instant.now());
    }

    public EventSource withSecretary(String agentId) {
        return new EventSource(owner, type, appId, enabled, ownerExternalId, bindCode, digestTime, timezone, agentId,
                lastDigestDate, lastHourlyAt, createdAt, Instant.now());
    }

    /** A new link for {@code owner}: the first digest is tomorrow's, and the hourly batch starts from now. */
    public static EventSource create(String owner, String type, String app, String externalId, String bindCode,
                                     String digestTime, String timezone, LocalDate today) {
        Instant now = Instant.now();
        return new EventSource(owner, type, app, true, externalId, bindCode, digestTime, timezone, null, today, now,
                now, now);
    }
}
