package com.superagent.api.inbox;

/** Save Feishu bot settings. A blank {@code appSecret} keeps the stored one. */
public record SaveInboxSourceRequest(String appId, String appSecret, Boolean enabled) {
}
