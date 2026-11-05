package com.superagent.platform.connector;

import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStatus;
import com.superagent.core.connector.ConnectorStore;
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
 * Drives the OAuth handshake for connectors backed by a real provider (currently
 * only GitHub). It issues an authorize URL with a one-time {@code state}, then on
 * callback exchanges the code for an access token, records a credential, and marks
 * the connector connected. Access tokens live only in the credential store and are
 * never logged or returned to clients.
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

    /** Connector ids whose Connect runs an OAuth handshake, mapped to the provider slug. */
    private static final Set<String> OAUTH_CONNECTORS = Set.of(GITHUB);

    private final ConnectorStore connectors;
    private final ConnectorCredentialStore credentials;
    private final GitHubOAuthClient github;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    private final Map<String, StateEntry> pendingStates = new ConcurrentHashMap<>();

    private record StateEntry(String connectorId, Instant createdAt) {
    }

    public ConnectorOAuthService(
            ConnectorStore connectors,
            ConnectorCredentialStore credentials,
            GitHubOAuthClient github,
            @Value("${superagent.oauth.github.client-id:}") String clientId,
            @Value("${superagent.oauth.github.client-secret:}") String clientSecret,
            @Value("${superagent.oauth.github.redirect-uri:http://localhost:8080/api/v1/oauth/github/callback}")
            String redirectUri) {
        this.connectors = connectors;
        this.credentials = credentials;
        this.github = github;
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

    /** The connected account label (e.g. GitHub login) for display, or {@code null}. */
    public String accountFor(String connectorId) {
        return credentials.find(connectorId).map(ConnectorCredential::accountLabel).orElse(null);
    }

    /** Builds the provider authorize URL and registers a one-time state for the connector. */
    public String buildAuthorizeUrl(String connectorId) {
        if (providerFor(connectorId) == null) {
            throw new OAuthException("no_oauth_provider");
        }
        connectors.findConnector(connectorId).orElseThrow(() -> new ConnectorNotFoundException(connectorId));
        if (!isConfigured()) {
            throw new OAuthException("github_not_configured");
        }
        purgeExpiredStates();
        String state = UUID.randomUUID().toString().replace("-", "");
        pendingStates.put(state, new StateEntry(connectorId, Instant.now()));
        return github.authorizeUrl(clientId, redirectUri, GITHUB_SCOPE, state);
    }

    /** Handles the provider callback; returns the connector id now connected. */
    public String handleCallback(String code, String state) {
        StateEntry entry = state == null ? null : pendingStates.remove(state);
        if (entry == null || Instant.now().isAfter(entry.createdAt().plus(STATE_TTL))) {
            throw new OAuthException("invalid_state");
        }
        if (code == null || code.isBlank()) {
            throw new OAuthException("missing_code");
        }
        try {
            String token = github.exchangeCodeForToken(clientId, clientSecret, code, redirectUri);
            String login = github.fetchLogin(token);
            credentials.save(new ConnectorCredential(entry.connectorId(), token, login, Instant.now()));
            Connector connector = connectors.findConnector(entry.connectorId())
                    .orElseThrow(() -> new ConnectorNotFoundException(entry.connectorId()));
            connectors.save(connector.withStatus(ConnectorStatus.CONNECTED));
            log.info("connector {} connected as GitHub @{}", entry.connectorId(), login);
            return entry.connectorId();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("exchange_failed");
        } catch (IOException e) {
            log.warn("GitHub OAuth exchange failed for {}: {}", entry.connectorId(), e.getMessage());
            throw new OAuthException("exchange_failed");
        }
    }

    /** Drops the stored credential when a connector is disconnected. */
    public void clearCredential(String connectorId) {
        credentials.delete(connectorId);
    }

    private void purgeExpiredStates() {
        Instant cutoff = Instant.now().minus(STATE_TTL);
        pendingStates.values().removeIf(e -> e.createdAt().isBefore(cutoff));
    }
}
