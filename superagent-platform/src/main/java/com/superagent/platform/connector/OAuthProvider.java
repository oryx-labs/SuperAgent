package com.superagent.platform.connector;

import java.io.IOException;
import java.time.Instant;

/**
 * One OAuth provider employees authorize a connector through (GitHub, Gitee). Its {@link #key()} is both the
 * connector's id and its integration's key under System Settings, where the app's client id and secret live.
 */
public interface OAuthProvider {

    /** What a code exchange or refresh returns. {@code refresh} and {@code expiresAt} are null for tokens that don't expire. */
    record Tokens(String access, String refresh, Instant expiresAt) {
    }

    /** The provider refused the refresh token (revoked, expired, app changed): the employee has to authorize again. */
    final class TokenRejectedException extends IOException {
        public TokenRejectedException(String message) {
            super(message);
        }
    }

    String key();

    String authorizeUrl(String clientId, String redirectUri, String state);

    Tokens exchange(String clientId, String clientSecret, String code, String redirectUri) throws IOException, InterruptedException;

    /** A new token for {@code refreshToken}; only called for tokens that carry one. */
    default Tokens refresh(String clientId, String clientSecret, String refreshToken) throws IOException, InterruptedException {
        throw new TokenRejectedException(key() + " tokens can't be refreshed");
    }

    /** The account's display name (login), for the connector row. */
    String fetchLogin(String accessToken) throws IOException, InterruptedException;
}
