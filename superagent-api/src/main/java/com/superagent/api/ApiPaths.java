package com.superagent.api;

/**
 * HTTP path constants shared by controllers and clients.
 *
 * <p>All endpoints are versioned by path. A breaking change introduces a new
 * version constant rather than altering an existing one, so older clients keep
 * working against the old prefix.
 */
public final class ApiPaths {

    /** Version prefix for every public endpoint. */
    public static final String V1 = "/api/v1";

    public static final String META = V1 + "/meta";

    private ApiPaths() {
    }
}
