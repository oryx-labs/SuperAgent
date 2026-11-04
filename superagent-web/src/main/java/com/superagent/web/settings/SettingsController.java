package com.superagent.web.settings;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.settings.SystemSettingsDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** System settings: any signed-in user may read them, only admins may change them (see SecurityConfig). */
@RestController
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping(ApiPaths.SETTINGS)
    public ApiResponse<SystemSettingsDto> get() {
        return ApiResponse.ok(settingsService.get());
    }

    @PutMapping(ApiPaths.SETTINGS)
    public ApiResponse<SystemSettingsDto> update(@RequestBody SystemSettingsDto request) {
        return ApiResponse.ok(settingsService.update(request));
    }
}
