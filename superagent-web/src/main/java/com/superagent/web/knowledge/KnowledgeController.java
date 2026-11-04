package com.superagent.web.knowledge;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.knowledge.KnowledgeEntryDto;
import com.superagent.api.knowledge.KnowledgeRequests;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The signed-in user's private knowledge base. The owner is always the authenticated user, never a parameter. */
@RestController
public class KnowledgeController {

    /** Types the browser may render inline; everything else is served as a download. */
    private static final Map<String, String> INLINE_TYPES = Map.ofEntries(
            Map.entry("md", "text/markdown;charset=UTF-8"), Map.entry("markdown", "text/markdown;charset=UTF-8"),
            Map.entry("txt", "text/plain;charset=UTF-8"), Map.entry("log", "text/plain;charset=UTF-8"),
            Map.entry("csv", "text/plain;charset=UTF-8"), Map.entry("json", "text/plain;charset=UTF-8"),
            Map.entry("yaml", "text/plain;charset=UTF-8"), Map.entry("yml", "text/plain;charset=UTF-8"),
            Map.entry("xml", "text/plain;charset=UTF-8"), Map.entry("html", "text/plain;charset=UTF-8"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("svg", "image/svg+xml"));

    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping(ApiPaths.KNOWLEDGE + "/entries")
    public ApiResponse<List<KnowledgeEntryDto>> list(Authentication auth, @RequestParam(defaultValue = "") String path) {
        return ApiResponse.ok(knowledge.list(auth.getName(), path));
    }

    @GetMapping(ApiPaths.KNOWLEDGE + "/folders")
    public ApiResponse<List<String>> folders(Authentication auth) {
        return ApiResponse.ok(knowledge.folders(auth.getName()));
    }

    @PostMapping(ApiPaths.KNOWLEDGE + "/folders")
    public ApiResponse<KnowledgeEntryDto> createFolder(Authentication auth, @RequestBody KnowledgeRequests.CreateFolder req) {
        return ApiResponse.ok(knowledge.createFolder(auth.getName(), req.path()));
    }

    @PostMapping(ApiPaths.KNOWLEDGE + "/rename")
    public ApiResponse<KnowledgeEntryDto> rename(Authentication auth, @RequestBody KnowledgeRequests.Rename req) {
        return ApiResponse.ok(knowledge.rename(auth.getName(), req.path(), req.newName()));
    }

    @PostMapping(ApiPaths.KNOWLEDGE + "/move")
    public ApiResponse<KnowledgeEntryDto> move(Authentication auth, @RequestBody KnowledgeRequests.Move req) {
        return ApiResponse.ok(knowledge.move(auth.getName(), req.path(), req.targetDir()));
    }

    @DeleteMapping(ApiPaths.KNOWLEDGE + "/entries")
    public ApiResponse<Void> delete(Authentication auth, @RequestParam String path) {
        knowledge.delete(auth.getName(), path);
        return ApiResponse.empty();
    }

    /**
     * Raw file for preview or download. nosniff + a sandboxing CSP keep an uploaded-by-agent SVG or
     * HTML from running script if opened directly; HTML is served as plain text for the same reason.
     */
    @GetMapping(ApiPaths.KNOWLEDGE + "/file")
    public ResponseEntity<byte[]> file(Authentication auth, @RequestParam String path,
                                       @RequestParam(defaultValue = "false") boolean download) {
        KnowledgeService.FileContent f = knowledge.read(auth.getName(), path);
        String ext = f.name().contains(".") ? f.name().substring(f.name().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String type = INLINE_TYPES.get(ext);
        boolean inline = type != null && !download;
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(f.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type != null ? type : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(f.bytes());
    }
}
