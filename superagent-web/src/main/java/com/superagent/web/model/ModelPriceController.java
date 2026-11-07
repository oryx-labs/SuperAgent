package com.superagent.web.model;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.provider.ModelPricesDto;
import com.superagent.cost.ModelPriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What each model costs, and whether tasks may use it (admin-only: under /admin, see SecurityConfig). */
@RestController
public class ModelPriceController {

    private final ModelPriceService prices;

    public ModelPriceController(ModelPriceService prices) {
        this.prices = prices;
    }

    @GetMapping(ApiPaths.ADMIN_MODEL_PRICES)
    public ApiResponse<ModelPricesDto> overview() {
        return ApiResponse.ok(prices.overview());
    }

    /** Reads the price directory now instead of waiting for the next check. */
    @PostMapping(ApiPaths.ADMIN_MODEL_PRICES + "/refresh")
    public ApiResponse<ModelPricesDto> refresh() {
        prices.refresh();
        return ApiResponse.ok(prices.overview());
    }

    /** Model ids may contain slashes, so the model goes in the query. */
    @PutMapping(ApiPaths.ADMIN_MODEL_PRICES + "/price")
    public ApiResponse<ModelPricesDto> setPrice(@RequestParam String model, @RequestBody ModelPricesDto.SetPrice body) {
        return ApiResponse.ok(prices.setPrice(model, body.input(), body.output(), body.cacheRead(), body.cacheWrite()));
    }

    @PutMapping(ApiPaths.ADMIN_MODEL_PRICES + "/tasks")
    public ApiResponse<ModelPricesDto> setForTasks(@RequestParam String model, @RequestBody ModelPricesDto.SetForTasks body) {
        return ApiResponse.ok(prices.setForTasks(model, body.forTasks()));
    }
}
