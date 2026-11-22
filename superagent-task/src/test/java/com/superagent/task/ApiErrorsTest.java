package com.superagent.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsTest {

    @Test
    void clientErrorsAreToldFromErrorsThatComeAndGo() {
        assertThat(ApiErrors.isClientError("API Error: 400 {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"Invalid signature in thinking block\"}}")).isTrue();
        assertThat(ApiErrors.isClientError("400 Bad Request")).isTrue();
        assertThat(ApiErrors.isClientError("API Error: 500 internal server error")).isFalse();
        assertThat(ApiErrors.isClientError("timed out after 600s")).isFalse();
        assertThat(ApiErrors.isClientError(null)).isFalse();
    }
}
