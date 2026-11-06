package com.superagent.web.connector;

import com.superagent.api.ApiPaths;
import com.superagent.platform.connector.ConnectorNotFoundException;
import com.superagent.platform.connector.ConnectorOAuthService;
import com.superagent.platform.connector.OAuthException;
import com.superagent.web.integration.AppUrl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * OAuth endpoints per provider (GitHub, Gitee), for an employee authorizing a connector with their own account. These are
 * reached by full-page browser navigation, so every outcome (success, cancel, misconfiguration) is a 302
 * back to the app with a query flag the UI surfaces — never a JSON error page.
 */
@RestController
public class ConnectorOAuthController {

    private final ConnectorOAuthService oauth;
    private final AppUrl appUrl;

    public ConnectorOAuthController(ConnectorOAuthService oauth, AppUrl appUrl) {
        this.oauth = oauth;
        this.appUrl = appUrl;
    }

    @GetMapping(ApiPaths.OAUTH + "/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String provider, @RequestParam String connectorId,
                                          Authentication auth, HttpServletRequest request) {
        try {
            return redirect(oauth.buildAuthorizeUrl(provider, connectorId, auth.getName(), appUrl.base(request)));
        } catch (OAuthException e) {
            return redirect(appReturn("connect_error", e.code()));
        } catch (ConnectorNotFoundException e) {
            return redirect(appReturn("connect_error", "unknown_connector"));
        }
    }

    @GetMapping(ApiPaths.OAUTH + "/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         Authentication auth) {
        if (error != null && !error.isBlank()) {
            return redirect(appReturn("connect_error", error));
        }
        try {
            return redirect(appReturn("connected", oauth.handleCallback(provider, code, state, auth.getName())));
        } catch (OAuthException e) {
            return redirect(appReturn("connect_error", e.code()));
        } catch (ConnectorNotFoundException e) {
            return redirect(appReturn("connect_error", "unknown_connector"));
        }
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    /** Back into the app, relative so it lands on whatever host the browser used. */
    private String appReturn(String key, String value) {
        return "/" + "?" + key + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8) + "#connectors";
    }
}
