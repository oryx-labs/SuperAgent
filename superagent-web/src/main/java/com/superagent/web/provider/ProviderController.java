package com.superagent.web.provider;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.provider.AddProviderRequest;
import com.superagent.api.provider.CatalogEntryDto;
import com.superagent.api.provider.ProviderDto;
import com.superagent.api.provider.CredentialDto;
import com.superagent.api.provider.CredentialRequests;
import com.superagent.api.provider.NodeRuntimeDto;
import com.superagent.platform.provider.CredentialService;
import com.superagent.platform.provider.FfmpegService;
import com.superagent.platform.provider.NodeRuntimeService;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.tenant.CompanyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Model-vendor CLI management (admin-only, enforced by SecurityConfig). */
@RestController
public class ProviderController {

    private final ProviderService service;
    private final NodeRuntimeService node;
    private final CredentialService credentials;
    private final CompanyService companies;
    private final FfmpegService ffmpeg;

    public ProviderController(ProviderService service, NodeRuntimeService node, CredentialService credentials,
                              CompanyService companies, FfmpegService ffmpeg) {
        this.companies = companies;
        this.ffmpeg = ffmpeg;
        this.service = service;
        this.node = node;
        this.credentials = credentials;
    }

    @GetMapping(ApiPaths.PROVIDERS)
    public ApiResponse<List<ProviderDto>> list() {
        return ApiResponse.ok(service.list());
    }

    /** The Node.js the npm-based CLIs need: which one is in use (system or private), or none. */
    @GetMapping(ApiPaths.PROVIDERS + "/node")
    public ApiResponse<NodeRuntimeDto> node() {
        return ApiResponse.ok(node.status());
    }

    /** Installs (or replaces) SuperAgent's private Node LTS; runs in the background, poll {@link #node()}. */
    @PostMapping(ApiPaths.PROVIDERS + "/node/install")
    public ApiResponse<NodeRuntimeDto> installNode() {
        return ApiResponse.ok(node.install());
    }

    /** The last private Node.js install's log (null data when there's been none since the server started). */
    @GetMapping(ApiPaths.PROVIDERS + "/node/install-log")
    public ApiResponse<com.superagent.api.provider.InstallLogDto> nodeInstallLog() {
        return ApiResponse.ok(node.installLog());
    }

    /** The server's ffmpeg (subtitles and transcription convert and cut audio with it): there or not, and its version. */
    @GetMapping(ApiPaths.PROVIDERS + "/ffmpeg")
    public ApiResponse<com.superagent.api.provider.FfmpegDto> ffmpeg() {
        return ApiResponse.ok(ffmpeg.status());
    }

    /** Installs ffmpeg with the system's package manager; runs in the background, poll {@link #ffmpeg()}. */
    @PostMapping(ApiPaths.PROVIDERS + "/ffmpeg/install")
    public ApiResponse<com.superagent.api.provider.FfmpegDto> installFfmpeg() {
        return ApiResponse.ok(ffmpeg.install());
    }

    @GetMapping(ApiPaths.PROVIDERS + "/ffmpeg/install-log")
    public ApiResponse<com.superagent.api.provider.InstallLogDto> ffmpegInstallLog() {
        return ApiResponse.ok(ffmpeg.installLog());
    }

    /** What the last install / upgrade of this CLI did and printed. */
    @GetMapping(ApiPaths.PROVIDERS + "/{id}/install-log")
    public ApiResponse<com.superagent.api.provider.InstallLogDto> installLog(@PathVariable String id) {
        return ApiResponse.ok(service.installLog(id));
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

    @PostMapping(ApiPaths.PROVIDERS + "/{id}/check-latest")
    public ApiResponse<ProviderDto> checkLatest(@PathVariable String id) {
        return ApiResponse.ok(service.checkLatest(id));
    }

    // --- the CLI's key pool (Claude Code, Codex) ---

    @GetMapping(ApiPaths.PROVIDERS + "/{id}/credentials")
    public ApiResponse<List<CredentialDto>> credentials(@PathVariable String id) {
        return ApiResponse.ok(credentials.list(service.keyOf(id)));
    }

    @PostMapping(ApiPaths.PROVIDERS + "/{id}/credentials")
    public ApiResponse<CredentialDto> addCredential(@PathVariable String id, @RequestBody CredentialRequests.Save request) {
        return ApiResponse.ok(credentials.add(service.keyOf(id), request));
    }

    @PutMapping(ApiPaths.PROVIDERS + "/{id}/credentials/{cid}")
    public ApiResponse<CredentialDto> updateCredential(@PathVariable String id, @PathVariable String cid,
                                                       @RequestBody CredentialRequests.Save request) {
        return ApiResponse.ok(credentials.update(service.keyOf(id), cid, request));
    }

    @DeleteMapping(ApiPaths.PROVIDERS + "/{id}/credentials/{cid}")
    public ApiResponse<Void> deleteCredential(@PathVariable String id, @PathVariable String cid) {
        credentials.delete(service.keyOf(id), cid);
        return ApiResponse.empty();
    }

    /** One real inference through this key now (the result also decides whether runs use it). */
    @PostMapping(ApiPaths.PROVIDERS + "/{id}/credentials/{cid}/check")
    public ApiResponse<CredentialDto> checkCredential(@PathVariable String id, @PathVariable String cid) {
        return ApiResponse.ok(credentials.checkNow(service.keyOf(id), cid));
    }

    @PutMapping(ApiPaths.PROVIDERS + "/{id}/credentials/order")
    public ApiResponse<List<CredentialDto>> reorderCredentials(@PathVariable String id,
                                                               @RequestBody CredentialRequests.Order request) {
        return ApiResponse.ok(credentials.reorder(service.keyOf(id), request.ids()));
    }
}
