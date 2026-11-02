package com.superagent.web;

import com.superagent.api.ApiError;
import com.superagent.core.SuperAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal endpoint used to verify the module wiring at startup.
 */
@RestController
public class MetaController {

    @GetMapping("/api/v1/meta")
    public ApiError meta() {
        return new ApiError(SuperAgent.NAME, "up");
    }
}
