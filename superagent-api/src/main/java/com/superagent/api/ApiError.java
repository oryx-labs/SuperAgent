package com.superagent.api;

import java.io.Serializable;

/**
 * Error body returned by the HTTP API.
 *
 * @param code    stable machine-readable code, e.g. {@code not_found}
 * @param message human-readable explanation
 */
public record ApiError(String code, String message) implements Serializable {
}
