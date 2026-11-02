package com.superagent.web;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.MetaResponse;
import com.superagent.core.SuperAgent;
import com.superagent.core.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the running build. Useful as a wiring check and as a client-side
 * version probe.
 */
@RestController
public class MetaController {

    private static final Logger log = LoggerFactory.getLogger(MetaController.class);

    private final String version;

    public MetaController(@Value("${superagent.version:unknown}") String version) {
        this.version = version;
    }

    @GetMapping(ApiPaths.META)
    public ApiResponse<MetaResponse> meta() {
        log.debug("meta requested, traceId={}", LogContext.getTraceId());
        return ApiResponse.ok(new MetaResponse(SuperAgent.NAME, "up", version));
    }
}
