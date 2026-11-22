package com.superagent.core.proxyconfirm;

import java.time.Instant;

/** One confirmation SuperAgent made for a user. {@code status}: confirmed | revoked. */
public record ProxyConfirmRecord(String id, String owner, String projectId, ConfirmType type, String objectType,
                                 String objectId, String reason, boolean revocable, String status, Instant at) {

    public static final String CONFIRMED = "confirmed";
    public static final String REVOKED = "revoked";

    public ProxyConfirmRecord revoked() {
        return new ProxyConfirmRecord(id, owner, projectId, type, objectType, objectId, reason, revocable, REVOKED, at);
    }
}
