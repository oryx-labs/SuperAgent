package com.superagent.core.inbox;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A user's connection to one message source (v1: their own Feishu bot). One per owner and type.
 *
 * <p>{@code secretCipher} is the app secret encrypted with {@link com.superagent.core.security.SecretCipher};
 * it is never returned to clients. {@code ownerExternalId} is the owner's own id at the source (their
 * Feishu open_id, learned through the bind code), used to spot mentions of them and to message them.
 * {@code vipNames} / {@code urgentKeywords} are newline-separated triage rules.
 */
public record EventSource(
        String owner,
        String type,
        String appId,
        String secretCipher,
        boolean enabled,
        String ownerExternalId,
        String bindCode,
        String vipNames,
        String urgentKeywords,
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

    /** Used when the user hasn't set their own urgent keywords. */
    public static final List<String> DEFAULT_URGENT_KEYWORDS = List.of("线上故障", "紧急", "加急", "今天必须", "宕机", "urgent");

    public EventSource withStatus(String newStatus, String newError) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, bindCode, vipNames,
                urgentKeywords, digestTime, timezone, secretaryAgentId, lastDigestDate, lastHourlyAt, newStatus,
                newError, createdAt, Instant.now());
    }

    public EventSource withBinding(String externalId) {
        return new EventSource(owner, type, appId, secretCipher, enabled, externalId, null, vipNames,
                urgentKeywords, digestTime, timezone, secretaryAgentId, lastDigestDate, lastHourlyAt, status,
                lastError, createdAt, Instant.now());
    }

    public EventSource withBindCode(String code) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, code, vipNames,
                urgentKeywords, digestTime, timezone, secretaryAgentId, lastDigestDate, lastHourlyAt, status,
                lastError, createdAt, Instant.now());
    }

    public EventSource withRuns(LocalDate digestDate, Instant hourlyAt) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, bindCode, vipNames,
                urgentKeywords, digestTime, timezone, secretaryAgentId, digestDate, hourlyAt, status, lastError,
                createdAt, Instant.now());
    }

    public EventSource withSecretary(String agentId) {
        return new EventSource(owner, type, appId, secretCipher, enabled, ownerExternalId, bindCode, vipNames,
                urgentKeywords, digestTime, timezone, agentId, lastDigestDate, lastHourlyAt, status, lastError,
                createdAt, Instant.now());
    }
}
