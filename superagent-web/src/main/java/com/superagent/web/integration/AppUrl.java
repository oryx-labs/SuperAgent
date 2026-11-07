package com.superagent.web.integration;

import com.superagent.platform.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * How browsers reach this server, for URLs handed to outside systems (OAuth callbacks): the public address set in
 * System Settings, else {@code superagent.app.base-url} (SUPERAGENT_BASE_URL, from before it could be set there),
 * else the scheme, host and port of the request — right unless a proxy in front hides them.
 */
@Component
public class AppUrl {

    private final SettingsService settings;
    private final String configured;

    public AppUrl(SettingsService settings, @Value("${superagent.app.base-url:}") String configured) {
        this.settings = settings;
        this.configured = configured == null ? "" : configured.trim();
    }

    public String base(HttpServletRequest request) {
        String set = settings.publicUrl();
        if (set != null) {
            return set;
        }
        if (!configured.isEmpty()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        return ServletUriComponentsBuilder.fromContextPath(request).replacePath(request.getContextPath())
                .replaceQuery(null).build().toUriString();
    }
}
