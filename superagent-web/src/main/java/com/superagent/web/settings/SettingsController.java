package com.superagent.web.settings;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.settings.SystemSettingsDto;
import com.superagent.platform.settings.BrandingService;
import com.superagent.platform.settings.SettingsService;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

/** System settings: any signed-in user may read them, only admins may change them (see SecurityConfig). */
@RestController
public class SettingsController {

    private final SettingsService settingsService;
    private final BrandingService branding;

    public SettingsController(SettingsService settingsService, BrandingService branding) {
        this.settingsService = settingsService;
        this.branding = branding;
    }

    @GetMapping(ApiPaths.SETTINGS)
    public ApiResponse<SystemSettingsDto> get() {
        return ApiResponse.ok(settingsService.get());
    }

    @PutMapping(ApiPaths.SETTINGS)
    public ApiResponse<SystemSettingsDto> update(@RequestBody SystemSettingsDto request) {
        return ApiResponse.ok(settingsService.update(request));
    }

    /** Whether a company logo replaces SuperAgent's. */
    @GetMapping(ApiPaths.SETTINGS + "/logo")
    public ApiResponse<Boolean> hasLogo() {
        return ApiResponse.ok(branding.custom());
    }

    @PutMapping(ApiPaths.SETTINGS + "/logo")
    public ApiResponse<Void> setLogo(@RequestBody Map<String, String> body) {
        branding.setLogo(body.get("data"));
        return ApiResponse.empty();
    }

    @DeleteMapping(ApiPaths.SETTINGS + "/logo")
    public ApiResponse<Void> resetLogo() {
        branding.resetLogo();
        return ApiResponse.empty();
    }

    /** The logo itself, for everyone (the sign-in page too). Revalidated on every load, so a change shows at once. */
    @GetMapping(ApiPaths.BRANDING_LOGO)
    public ResponseEntity<byte[]> logo() {
        ImageAttachmentStore.StoredImage logo = branding.logo();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.mediaType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache())
                .eTag(Integer.toHexString(Arrays.hashCode(logo.bytes())))
                .body(logo.bytes());
    }
}
