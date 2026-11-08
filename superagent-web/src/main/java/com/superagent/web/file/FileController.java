package com.superagent.web.file;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.file.FileFolderDto;
import com.superagent.api.file.WorkFileDto;
import com.superagent.api.file.WorkFileRequests;
import com.superagent.files.FileFolderService;
import com.superagent.files.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Work files: list, read, upload, rename, delete. */
@RestController
public class FileController {

    /**
     * Types shown in the page rather than downloaded. Text kinds are served as plain text (the UI renders
     * Markdown itself), so a produced HTML or SVG never runs as a page on this origin.
     */
    private static final Map<String, String> INLINE_TYPES = Map.ofEntries(
            Map.entry("md", "text/plain;charset=UTF-8"), Map.entry("markdown", "text/plain;charset=UTF-8"),
            Map.entry("txt", "text/plain;charset=UTF-8"), Map.entry("csv", "text/plain;charset=UTF-8"),
            Map.entry("json", "text/plain;charset=UTF-8"), Map.entry("html", "text/plain;charset=UTF-8"),
            Map.entry("htm", "text/plain;charset=UTF-8"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("svg", "image/svg+xml"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"),
            Map.entry("m4v", "video/mp4"), Map.entry("pdf", "application/pdf"));

    private final FileService files;
    private final FileFolderService folders;

    public FileController(FileService files, FileFolderService folders) {
        this.files = files;
        this.folders = folders;
    }

    @GetMapping(ApiPaths.FILE_FOLDERS)
    public ApiResponse<List<FileFolderDto>> folders(Authentication auth) {
        return ApiResponse.ok(folders.list(auth.getName()));
    }

    @PostMapping(ApiPaths.FILE_FOLDERS)
    public ApiResponse<FileFolderDto> createFolder(@RequestBody WorkFileRequests.SaveFolder request, Authentication auth) {
        return ApiResponse.ok(folders.create(auth.getName(), request.name(), request.parentId()));
    }

    @PutMapping(ApiPaths.FILE_FOLDERS + "/{id}")
    public ApiResponse<FileFolderDto> renameFolder(@PathVariable String id, @RequestBody WorkFileRequests.SaveFolder request,
                                                   Authentication auth) {
        return ApiResponse.ok(folders.rename(auth.getName(), id, request.name()));
    }

    /** Its files and subfolders go up a level; no file is deleted. */
    @DeleteMapping(ApiPaths.FILE_FOLDERS + "/{id}")
    public ApiResponse<Void> deleteFolder(@PathVariable String id, Authentication auth) {
        folders.delete(auth.getName(), id);
        return ApiResponse.empty();
    }

    @PutMapping(ApiPaths.FILES + "/{id}/folder")
    public ApiResponse<WorkFileDto> move(@PathVariable String id, @RequestBody WorkFileRequests.Move request, Authentication auth) {
        folders.move(auth.getName(), id, request.folderId());
        return ApiResponse.ok(files.get(id, auth.getName()));
    }

    /** Everything the caller can see, newest first; {@code agent} narrows to one agent's files, {@code task} to one task's. */
    @GetMapping(ApiPaths.FILES)
    public ApiResponse<List<WorkFileDto>> list(@RequestParam(required = false) String agent,
                                               @RequestParam(required = false) String task,
                                               @RequestParam(required = false) String project, Authentication auth) {
        if (project != null && !project.isBlank()) {
            return ApiResponse.ok(files.listForProject(auth.getName(), project.strip()));
        }
        if (task != null && !task.isBlank()) {
            return ApiResponse.ok(files.listForTask(auth.getName(), task.strip()));
        }
        return ApiResponse.ok(files.list(auth.getName(), agent));
    }

    @GetMapping(ApiPaths.FILES + "/{id}")
    public ApiResponse<WorkFileDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(files.get(id, auth.getName()));
    }

    /** The content: inline for previewable types (videos support seeking via Range), otherwise a download. */
    @GetMapping(ApiPaths.FILES + "/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable String id, @RequestParam(defaultValue = "false") boolean download,
                                            Authentication auth) {
        FileService.Content c = files.content(id, auth.getName());
        String name = c.name();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String type = INLINE_TYPES.get(ext);
        boolean inline = type != null && !download;
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8).build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(inline ? type : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!"pdf".equals(ext)) { // a sandboxed CSP would also block the browser's PDF viewer
            response.header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; media-src 'self'");
        }
        return response.body(c.resource());
    }

    /**
     * A file to attach to the next message to an agent ({@code agent}) or a task ({@code task}); with neither, one the
     * user keeps in the Files page, placed in {@code folder} if given.
     */
    @PostMapping(ApiPaths.FILES + "/uploads")
    public ApiResponse<WorkFileDto> upload(@RequestParam(required = false) String agent,
                                           @RequestParam(required = false) String task,
                                           @RequestParam(required = false) String folder,
                                           @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        WorkFileDto saved;
        try (InputStream in = file.getInputStream()) {
            saved = files.upload(auth.getName(), agent, task, file.getOriginalFilename(), in);
        }
        return ApiResponse.ok(placed(saved, folder, auth));
    }

    /** A Markdown file written in the Files page. */
    @PostMapping(ApiPaths.FILES + "/markdown")
    public ApiResponse<WorkFileDto> createMarkdown(@RequestBody WorkFileRequests.CreateMarkdown request, Authentication auth) {
        return ApiResponse.ok(placed(files.createMarkdown(auth.getName(), request.name(), request.content()),
                request.folderId(), auth));
    }

    @PutMapping(ApiPaths.FILES + "/{id}/content")
    public ApiResponse<WorkFileDto> editContent(@PathVariable String id, @RequestBody WorkFileRequests.EditContent request,
                                                Authentication auth) {
        return ApiResponse.ok(files.editText(id, auth.getName(), request.content()));
    }

    private WorkFileDto placed(WorkFileDto file, String folder, Authentication auth) {
        if (folder == null || folder.isBlank()) {
            return file;
        }
        folders.move(auth.getName(), file.id(), folder);
        return files.get(file.id(), auth.getName());
    }

    @PutMapping(ApiPaths.FILES + "/{id}")
    public ApiResponse<WorkFileDto> rename(@PathVariable String id, @RequestBody WorkFileRequests.Rename request,
                                           Authentication auth) {
        return ApiResponse.ok(files.rename(id, auth.getName(), request.name()));
    }

    /** A person sets the file's tags; from then on they win over the agent's and the tagger's. */
    @PutMapping(ApiPaths.FILES + "/{id}/tags")
    public ApiResponse<WorkFileDto> setTags(@PathVariable String id, @RequestBody WorkFileRequests.Tags request,
                                            Authentication auth) {
        return ApiResponse.ok(files.setTags(id, auth.getName(), request.tags()));
    }

    @DeleteMapping(ApiPaths.FILES + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        files.delete(id, auth.getName());
        return ApiResponse.empty();
    }
}
