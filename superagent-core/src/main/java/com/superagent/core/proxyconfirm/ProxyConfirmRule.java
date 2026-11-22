package com.superagent.core.proxyconfirm;

import java.time.Instant;
import java.util.Set;

/** A user's "confirm for me" settings; {@code projectId} empty = the user's global rule, else that project's override. */
public record ProxyConfirmRule(String owner, String projectId, boolean enabled, Set<ConfirmType> types, Instant updatedAt) {

    public static final String GLOBAL = "";

    public boolean allows(ConfirmType type) {
        return enabled && type.allowed() && types.contains(type);
    }
}
