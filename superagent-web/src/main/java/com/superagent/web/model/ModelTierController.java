package com.superagent.web.model;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.model.ModelTiersDto;
import com.superagent.api.model.PlaceModelRequest;
import com.superagent.cost.ModelTierService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The tiers people can pick from, and (admin-only: under /admin, see SecurityConfig) how models landed in them. */
@RestController
public class ModelTierController {

    private final ModelTierService tiers;

    public ModelTierController(ModelTierService tiers) {
        this.tiers = tiers;
    }

    @GetMapping(ApiPaths.MODEL_TIERS)
    public ApiResponse<List<ModelTiersDto.Tier>> tiers() {
        return ApiResponse.ok(tiers.tiers());
    }

    @GetMapping(ApiPaths.ADMIN_MODEL_TIERS)
    public ApiResponse<ModelTiersDto> overview() {
        return ApiResponse.ok(tiers.overview());
    }

    /** Reads the price directory now instead of waiting for the next check. */
    @PostMapping(ApiPaths.ADMIN_MODEL_TIERS + "/refresh")
    public ApiResponse<ModelTiersDto> refresh() {
        tiers.refresh();
        return ApiResponse.ok(tiers.overview());
    }

    @PutMapping(ApiPaths.ADMIN_MODEL_TIERS + "/place")
    public ApiResponse<ModelTiersDto> place(@RequestBody PlaceModelRequest request) {
        return ApiResponse.ok(tiers.place(request.modelId(), request.tier()));
    }
}
