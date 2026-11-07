package com.superagent.platform.connector;

import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.security.SecretCipher;
import com.superagent.platform.integration.IntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drives the OAuth handshake for connectors backed by a real provider (GitHub, Gitee), once per employee: it issues
 * an authorize URL with a one-time {@code state} tied to who asked, then on callback exchanges the code for an access
 * token and stores it, encrypted, as that employee's credential. Tokens are never logged or returned to clients.
 *
 * <p>Each provider's OAuth app (client id / secret) is set by an administrator under System Settings → integrations;
 * {@link #isConfigured} gates the flow so an unconfigured install fails with a clear message instead of a broken
 * redirect. The callback URL is derived from how the server is reached, so it can't name a wrong port.
 *
 * <p>Tokens that expire (Gitee's, after a day) are renewed with their refresh token when a run is about to use
 * them ({@link #accessToken}). A refresh the provider refuses drops the credential, so the connector shows as
 * not authorized and the employee authorizes again.
 */
@Service
public class ConnectorOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorOAuthService.class);

    public static final String GITHUB = GitHubOAuthClient.KEY;
    public static final String GITEE = GiteeOAuthClient.KEY;
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    /** Renew this long before expiry, so a run never starts with a token that dies halfway through. */
    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(10);

    private final ConnectorStore connectors;
    private final ConnectorCredentialStore credentials;
    private final Map<String, OAuthProvider> providers;
    private final SecretCipher cipher;
    private final IntegrationService integrations;

    private final Map<String, StateEntry> pendingStates = new ConcurrentHashMap<>();
    /** One refresh at a time per credential: a refresh token may be good for one use only. */
    private final Map<String, Object> refreshLocks = new ConcurrentHashMap<>();

    private record StateEntry(String connectorId, String owner, String redirectUri, Instant createdAt) {
    }

    public ConnectorOAuthService(ConnectorStore connectors, ConnectorCredentialStore credentials,
                                 List<OAuthProvider> providers, SecretCipher cipher, IntegrationService integrations) {
        this.connectors = connectors;
        this.credentials = credentials;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(OAuthProvider::key, Function.identity()));
        this.cipher = cipher;
        this.integrations = integrations;
    }

    /** The provider's app is enabled and filled in under System Settings. */
    public boolean isConfigured(String provider) {
        return provider != null && providers.containsKey(provider) && integrations.ready(providers.get(provider).integration());
    }

    /** The provider slug if this connector uses an OAuth handshake, else {@code null}. */
    public String providerFor(String connectorId) {
        return providers.containsKey(connectorId) ? connectorId : null;
    }

    /**
     * Builds the provider authorize URL and registers a one-time state for {@code owner} and the connector.
     * {@code baseUrl} is how the browser reaches this server; the callback must match what is registered at the provider.
     */
    public String buildAuthorizeUrl(String provider, String connectorId, String owner, String baseUrl) {
        OAuthProvider p = providers.get(provider);
        if (p == null || !provider.equals(providerFor(connectorId))) {
            throw new OAuthException("no_oauth_provider");
        }
        connectors.findConnector(connectorId).orElseThrow(() -> new ConnectorNotFoundException(connectorId));
        if (!isConfigured(provider)) {
            throw new OAuthException("not_configured");
        }
        purgeExpiredStates();
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirectUri = IntegrationService.callbackUrl(provider, baseUrl);
        pendingStates.put(state, new StateEntry(connectorId, owner, redirectUri, Instant.now()));
        return p.authorizeUrl(clientId(provider), redirectUri, state);
    }

    /**
     * Handles {@code provider}'s callback for the employee who started it; returns the connector id now authorized.
     * The state must have been issued to {@code caller} for this provider, so one person's callback can't land on
     * another's account.
     */
    public String handleCallback(String provider, String code, String state, String caller) {
        StateEntry entry = state == null ? null : pendingStates.remove(state);
        if (entry == null || Instant.now().isAfter(entry.createdAt().plus(STATE_TTL)) || !entry.owner().equals(caller)
                || !provider.equals(providerFor(entry.connectorId()))) {
            throw new OAuthException("invalid_state");
        }
        if (code == null || code.isBlank()) {
            throw new OAuthException("missing_code");
        }
        OAuthProvider p = providers.get(provider);
        try {
            if (!isConfigured(provider)) {
                throw new OAuthException("not_configured");
            }
            OAuthProvider.Tokens tokens = p.exchange(clientId(provider), clientSecret(provider), code, entry.redirectUri());
            String login = p.fetchLogin(tokens.access());
            credentials.save(credential(entry.connectorId(), entry.owner(), tokens, login));
            log.info("connector {} authorized by {} as {} @{}", entry.connectorId(), entry.owner(), provider, login);
            return entry.connectorId();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("exchange_failed");
        } catch (IOException e) {
            log.warn("{} OAuth exchange failed for {}: {}", provider, entry.connectorId(), e.getMessage());
            throw new OAuthException("exchange_failed");
        }
    }

    /**
     * The access token a run should use for this credential, renewed first when it's about to expire. Empty when it
     * can't be had: the provider refused the refresh (the credential is dropped), or it has expired and the provider
     * can't be reached right now (kept, for the next run to try again).
     */
    public Optional<String> accessToken(ConnectorCredential credential) {
        if (!needsRefresh(credential)) {
            return decrypt(credential);
        }
        String provider = providerFor(credential.connectorId());
        synchronized (refreshLocks.computeIfAbsent(credential.connectorId() + "/" + credential.owner(), k -> new Object())) {
            // Another run may have renewed it while this one waited.
            ConnectorCredential current = credentials.find(credential.connectorId(), credential.owner()).orElse(null);
            if (current == null) {
                return Optional.empty();
            }
            if (!needsRefresh(current)) {
                return decrypt(current);
            }
            if (provider == null || current.refreshCipher() == null || !isConfigured(provider)) {
                return stillValid(current) ? decrypt(current) : Optional.empty();
            }
            try {
                OAuthProvider.Tokens tokens = providers.get(provider).refresh(clientId(provider), clientSecret(provider),
                        cipher.decrypt(current.refreshCipher()));
                credentials.save(credential(current.connectorId(), current.owner(),
                        tokens.refresh() != null ? tokens : new OAuthProvider.Tokens(tokens.access(),
                                cipher.decrypt(current.refreshCipher()), tokens.expiresAt()), current.accountLabel()));
                log.info("connector {}: renewed {}'s token", current.connectorId(), current.owner());
                return Optional.of(tokens.access());
            } catch (OAuthProvider.TokenRejectedException e) {
                log.warn("connector {}: {} has to authorize again ({})", current.connectorId(), current.owner(), e.getMessage());
                credentials.delete(current.connectorId(), current.owner());
                return Optional.empty();
            } catch (IOException | IllegalStateException e) {
                log.warn("connector {}: couldn't renew {}'s token: {}", current.connectorId(), current.owner(), e.getMessage());
                return stillValid(current) ? decrypt(current) : Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    private static boolean needsRefresh(ConnectorCredential c) {
        return c.expiresAt() != null && Instant.now().plus(REFRESH_AHEAD).isAfter(c.expiresAt());
    }

    private static boolean stillValid(ConnectorCredential c) {
        return c.expiresAt() == null || Instant.now().isBefore(c.expiresAt());
    }

    private Optional<String> decrypt(ConnectorCredential c) {
        try {
            return Optional.of(cipher.decrypt(c.secretCipher()));
        } catch (IllegalStateException e) {
            log.warn("connector {}: stored token can't be decrypted, skipping it", c.connectorId());
            return Optional.empty();
        }
    }

    private ConnectorCredential credential(String connectorId, String owner, OAuthProvider.Tokens tokens, String login) {
        return new ConnectorCredential(connectorId, owner, cipher.encrypt(tokens.access()), login, Instant.now(),
                tokens.refresh() == null ? null : cipher.encrypt(tokens.refresh()), tokens.expiresAt());
    }

    private String clientId(String provider) {
        OAuthProvider p = providers.get(provider);
        return integrations.value(p.integration(), p.clientIdField()).orElse("");
    }

    private String clientSecret(String provider) {
        OAuthProvider p = providers.get(provider);
        return integrations.value(p.integration(), p.clientSecretField()).orElseThrow(() -> new OAuthException("not_configured"));
    }

    private void purgeExpiredStates() {
        Instant cutoff = Instant.now().minus(STATE_TTL);
        pendingStates.values().removeIf(e -> e.createdAt().isBefore(cutoff));
    }
}
