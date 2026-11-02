package com.superagent.api;

import java.io.Serializable;

/**
 * Payload of {@code GET /api/v1/meta}.
 *
 * @param name    platform name
 * @param status  liveness marker, {@code up} when the process is serving
 * @param version build version, or {@code unknown}
 */
public record MetaResponse(String name, String status, String version) implements Serializable {
}
