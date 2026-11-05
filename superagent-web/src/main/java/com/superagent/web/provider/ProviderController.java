package com.superagent.web.provider;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.provider.AddProviderRequest;
import com.superagent.api.provider.CatalogEntryDto;
import com.superagent.api.provider.ProviderDto;
import com.superagent.platform.provider.ProviderService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Model-vendor CLI management (admin-only, enforced by SecurityConfig). */
@RestController
public class ProviderController {

    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    @GetMapping(ApiPaths.PROVIDERS)
    public ApiResponse<List<ProviderDto>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping(ApiPaths.PROVIDERS + "/catalog")
    public ApiResponse<List<CatalogEntryDto>> catalog() {
        return ApiResponse.ok(service.catalog());
    }

    @PostMapping(ApiPaths.PROVIDERS)
    public ApiResponse<ProviderDto> add(@RequestBody AddProviderRequest request) {
        return ApiResponse.ok(service.add(request.key()));
    }

    @GetMapping(ApiPaths.PROVIDERS + "/{id}")
    public ApiResponse<ProviderDto> detail(@PathVariable String id) {
        return ApiResponse.ok(service.detail(id));
    }

    @DeleteMapping(ApiPaths.PROVIDERS + "/{id}")
    public ApiResponse<Void> remove(@PathVariable String id) {
        service.remove(id);
        return ApiResponse.empty();
    }

    @PostMapping(ApiPaths.PROVIDERS + "/{id}/install")
    public ApiResponse<ProviderDto> install(@PathVariable String id) {
        return ApiResponse.ok(service.install(id));
    }

    @PostMapping(ApiPaths.PROVIDERS + "/{id}/upgrade")
    public ApiResponse<ProviderDto> upgrade(@PathVariable String id) {
        return ApiResponse.ok(service.upgrade(id));
    }

    @PostMapping(ApiPaths.PROVIDERS + "/{id}/models/refresh")
    public ApiResponse<ProviderDto> refreshModels(@PathVariable String id) {
        return ApiResponse.ok(service.refreshModelsNow(id));
    }
}
