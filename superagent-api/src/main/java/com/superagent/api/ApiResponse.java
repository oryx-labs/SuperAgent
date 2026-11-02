package com.superagent.api;

import java.io.Serializable;

/**
 * Uniform response envelope for every HTTP endpoint.
 *
 * <p>Clients switch on {@link #code}; {@link #message} is for humans and may
 * change at any time. {@link #data} is absent when the operation produced no
 * payload (deletes, acknowledgements).
 *
 * @param code    stable machine-readable outcome, {@link #CODE_OK} on success
 * @param message human-readable explanation
 * @param data    payload, or {@code null}
 */
public record ApiResponse<T>(String code, String message, T data) implements Serializable {

    public static final String CODE_OK = "ok";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_OK, "success", data);
    }

    public static <T> ApiResponse<T> empty() {
        return new ApiResponse<>(CODE_OK, "success", null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
