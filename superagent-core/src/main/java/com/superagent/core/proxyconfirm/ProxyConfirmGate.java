package com.superagent.core.proxyconfirm;

import java.util.Map;

/**
 * Asked by the places that wait for a person's confirmation: may SuperAgent confirm this for them? Implemented in
 * the orchestrator; callers treat anything but {@link Decision#confirmed()} (including a failure) as "ask the person".
 */
public interface ProxyConfirmGate {

    /** {@code facts}: what the caller knows about the thing, e.g. {@code openParams}, {@code criteria}, {@code commands}, {@code prdAligned}. */
    Decision decide(String owner, String projectId, ConfirmType type, String objectType, String objectId, Map<String, Object> facts);

    /** The type of an unused, still valid confirmation made for {@code objectId}; empty when there is none such. */
    java.util.Optional<ConfirmType> claimable(String owner, String recordId, String objectId);

    /** Points a confirmation at the thing it made once that exists (a task created after the confirmation). */
    void attach(String owner, String recordId, String objectId);

    record Decision(boolean confirmed, String reason, String recordId) {
        public static Decision refer(String reason) {
            return new Decision(false, reason, null);
        }
    }
}
