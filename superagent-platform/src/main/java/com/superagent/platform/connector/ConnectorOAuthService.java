package com.superagent.platform.connector;

import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the OAuth handshake for connectors backed by a real provider (currently only GitHub), once per
 * employee: it issues an authorize URL with a one-time {@code state} tied to who asked, then on callback
 * exchanges the code for an access token and stores it, encrypted, as that employee's credential. Tokens
 * are never logged or returned to clients.
 *
 * <p>App credentials come from configuration (env-backed) and are absent by
 * default; {@link #isConfigured()} gates the flow so an unconfigured install fails
 * with a clear message instead of a broken redirect.
 */
@Service
public class ConnectorOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorOAuthService.class);

    public static final String GITHUB = "github";
    private static final String GITHUB_SCOPE = "repo read:user";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    /** Connector ids whose authorization runs an OAuth handshake, mapped to the provider slug. */
    private static final Set<String> OAUTH_CONNECTORS = Set.of(GITHUB);

    private final ConnectorStore connectors;
    private final ConnectorCredentialStore credentials;
    private final GitHubOAuthClient github;
    private final SecretCipher cipher;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    private final Map<String, StateEntry> pendingStates = new ConcurrentHashMap<>();

    private record StateEntry(String connectorId, String owner, Instant createdAt) {
    }

    public ConnectorOAuthService(
            ConnectorStore connectors,
            ConnectorCredentialStore credentials,
            GitHubOAuthClient github,
            SecretCipher cipher,
            @Value("${superagent.oauth.github.client-id:}") String clientId,
            @Value("${superagent.oauth.github.client-secret:}") String clientSecret,
            @Value("${superagent.oauth.github.redirect-uri:http://localhost:18080/api/v1/oauth/github/callback}")
            String redirectUri) {
        this.connectors = connectors;
        this.credentials = credentials;
        this.github = github;
        this.cipher = cipher;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri;
        log.info("ConnectorOAuthService ready: github OAuth {}", isConfigured() ? "configured" : "NOT configured");
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** The provider slug if this connector uses an OAuth handshake, else {@code null}. */
    public String providerFor(String connectorId) {
        return OAUTH_CONNECTORS.contains(connectorId) ? connectorId : null;
    }

    /** Builds the provider authorize URL and registers a one-time state for {@code owner} and the connector. */
    public String buildAuthorizeUrl(String connectorId, String owner) {
        if (providerFor(connectorId) == null) {
            throw new OAuthException("no_oauth_provider");
        }
        connectors.findConnector(connectorId).orElseThrow(() -> new ConnectorNotFoundException(connectorId));
        if (!isConfigured()) {
            throw new OAuthException("github_not_configured");
        }
        purgeExpiredStates();
        String state = UUID.randomUUID().toString().replace("-", "");
        pendingStates.put(state, new StateEntry(connectorId, owner, Instant.now()));
        return github.authorizeUrl(clientId, redirectUri, GITHUB_SCOPE, state);
    }

    /**
     * Handles the provider callback for the employee who started it; returns the connector id now authorized.
     * The state must have been issued to {@code caller}, so one person's callback can't land on another's account.
     */
    public String handleCallback(String code, String state, String caller) {
        StateEntry entry = state == null ? null : pendingStates.remove(state);
        if (entry == null || Instant.now().isAfter(entry.createdAt().plus(STATE_TTL)) || !entry.owner().equals(caller)) {
            throw new OAuthException("invalid_state");
        }
        if (code == null || code.isBlank()) {
            throw new OAuthException("missing_code");
        }
        try {
            String token = github.exchangeCodeForToken(clientId, clientSecret, code, redirectUri);
            String login = github.fetchLogin(token);
            credentials.save(new ConnectorCredential(entry.connectorId(), entry.owner(), cipher.encrypt(token), login,
                    Instant.now()));
            log.info("connector {} authorized by {} as GitHub @{}", entry.connectorId(), entry.owner(), login);
            return entry.connectorId();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("exchange_failed");
        } catch (IOException e) {
            log.warn("GitHub OAuth exchange failed for {}: {}", entry.connectorId(), e.getMessage());
            throw new OAuthException("exchange_failed");
        }
    }

    private void purgeExpiredStates() {
        Instant cutoff = Instant.now().minus(STATE_TTL);
        pendingStates.values().removeIf(e -> e.createdAt().isBefore(cutoff));
    }
}
