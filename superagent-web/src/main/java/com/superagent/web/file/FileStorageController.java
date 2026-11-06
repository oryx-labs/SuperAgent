package com.superagent.web.file;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.file.FileStorageDto;
import com.superagent.api.file.SaveFileStorageRequest;
import com.superagent.files.FileStorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Where Files keeps content (admin-only: under /admin, see SecurityConfig). */
@RestController
public class FileStorageController {

    private final FileStorageService storage;

    public FileStorageController(FileStorageService storage) {
        this.storage = storage;
    }

    @GetMapping(ApiPaths.FILE_STORAGE)
    public ApiResponse<FileStorageDto> get() {
        return ApiResponse.ok(storage.view());
    }

    /** Checks the settings with a real write, read and delete, then makes this store the one new files go to. */
    @PutMapping(ApiPaths.FILE_STORAGE)
    public ApiResponse<FileStorageDto> save(@RequestBody SaveFileStorageRequest request) {
        return ApiResponse.ok(storage.save(request.type(), request.values()));
    }

    @PostMapping(ApiPaths.FILE_STORAGE + "/test")
    public ApiResponse<Void> test(@RequestBody SaveFileStorageRequest request) {
        storage.test(request.type(), request.values());
        return ApiResponse.empty();
    }
}
