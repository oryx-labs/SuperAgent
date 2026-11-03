package com.superagent.core.user;

/** How a user authenticates / was provisioned. A user has one primary source. */
public enum UserSource {
    LOCAL,
    PHONE,
    SSO,
    WECHAT
}
