package com.superagent.web.handover;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.handover.HandoverPreviewDto;
import com.superagent.api.handover.HandoverRequests;
import com.superagent.api.handover.HandoverResultDto;
import com.superagent.handover.HandoverService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only employee handover (enforced by SecurityConfig on {@code /api/v1/admin/**}). */
@RestController
public class HandoverController {

    private final HandoverService handover;

    public HandoverController(HandoverService handover) {
        this.handover = handover;
    }

    @PostMapping(ApiPaths.HANDOVER + "/preview")
    public ApiResponse<HandoverPreviewDto> preview(@RequestBody HandoverRequests.PreviewRequest request) {
        return ApiResponse.ok(handover.preview(request.fromUsername(), request.toUsername()));
    }

    @PostMapping(ApiPaths.HANDOVER)
    public ApiResponse<HandoverResultDto> execute(@RequestBody HandoverRequests.ExecuteRequest request,
                                                  Authentication auth) {
        boolean disable = request.disableFromUser() == null || request.disableFromUser();
        return ApiResponse.ok(handover.execute(request.fromUsername(), request.toUsername(), disable, auth.getName()));
    }
}
