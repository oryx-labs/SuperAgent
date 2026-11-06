package com.superagent.web.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * How browsers reach this server, for URLs handed to outside systems (OAuth callbacks): the configured
 * {@code superagent.app.base-url} when set (behind a proxy), else the scheme, host and port of the request.
 */
@Component
public class AppUrl {

    private final String configured;

    public AppUrl(@Value("${superagent.app.base-url:}") String configured) {
        this.configured = configured == null ? "" : configured.trim();
    }

    public String base(HttpServletRequest request) {
        if (!configured.isEmpty()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        return ServletUriComponentsBuilder.fromContextPath(request).replacePath(request.getContextPath())
                .replaceQuery(null).build().toUriString();
    }
}
