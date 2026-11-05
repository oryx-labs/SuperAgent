package com.superagent.web.analytics;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.analytics.AnalyticsDto;
import com.superagent.platform.analytics.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin analytics (admin-only, see SecurityConfig). */
@RestController
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping(ApiPaths.ANALYTICS)
    public ApiResponse<AnalyticsDto> overview(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(analytics.overview(days));
    }
}
