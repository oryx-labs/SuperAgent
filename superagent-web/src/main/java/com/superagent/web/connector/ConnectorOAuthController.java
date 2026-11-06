package com.superagent.web.connector;

import com.superagent.api.ApiPaths;
import com.superagent.platform.connector.ConnectorNotFoundException;
import com.superagent.platform.connector.ConnectorOAuthService;
import com.superagent.platform.connector.OAuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * GitHub OAuth endpoints, for an employee authorizing a connector with their own account. These are
 * reached by full-page browser navigation, so every outcome (success, cancel, misconfiguration) is a 302
 * back to the app with a query flag the UI surfaces — never a JSON error page.
 */
@RestController
public class ConnectorOAuthController {

    private final ConnectorOAuthService oauth;
    private final String appBaseUrl;

    public ConnectorOAuthController(ConnectorOAuthService oauth,
                                    @Value("${superagent.app.base-url:}") String appBaseUrl) {
        this.oauth = oauth;
        this.appBaseUrl = appBaseUrl == null ? "" : appBaseUrl.trim();
    }

    @GetMapping(ApiPaths.OAUTH + "/github/authorize")
    public ResponseEntity<Void> authorize(@RequestParam String connectorId, Authentication auth) {
        try {
            return redirect(oauth.buildAuthorizeUrl(connectorId, auth.getName()));
        } catch (OAuthException e) {
            return redirect(appReturn("connect_error", e.code()));
        } catch (ConnectorNotFoundException e) {
            return redirect(appReturn("connect_error", "unknown_connector"));
        }
    }

    @GetMapping(ApiPaths.OAUTH + "/github/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         Authentication auth) {
        if (error != null && !error.isBlank()) {
            return redirect(appReturn("connect_error", error));
        }
        try {
            return redirect(appReturn("connected", oauth.handleCallback(code, state, auth.getName())));
        } catch (OAuthException e) {
            return redirect(appReturn("connect_error", e.code()));
        } catch (ConnectorNotFoundException e) {
            return redirect(appReturn("connect_error", "unknown_connector"));
        }
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private String appReturn(String key, String value) {
        String base = appBaseUrl.isBlank() ? "/" : appBaseUrl;
        return base + "?" + key + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8) + "#connectors";
    }
}
