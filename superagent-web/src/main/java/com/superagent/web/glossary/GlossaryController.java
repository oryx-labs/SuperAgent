package com.superagent.web.glossary;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.glossary.GlossaryTermDto;
import com.superagent.api.glossary.GlossaryTermRequest;
import com.superagent.glossary.GlossaryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The glossary: the company's ({@code projectId} absent; admins change it) and each project's (its members change it). */
@RestController
public class GlossaryController {

    private final GlossaryService glossary;

    public GlossaryController(GlossaryService glossary) {
        this.glossary = glossary;
    }

    @GetMapping(ApiPaths.GLOSSARY)
    public ApiResponse<List<GlossaryTermDto>> list(@RequestParam(required = false) String projectId, Authentication auth) {
        return ApiResponse.ok(glossary.list(blank(projectId), auth.getName()));
    }

    @PostMapping(ApiPaths.GLOSSARY)
    public ApiResponse<GlossaryTermDto> create(@RequestParam(required = false) String projectId,
                                               @RequestBody GlossaryTermRequest request, Authentication auth) {
        return ApiResponse.ok(glossary.create(blank(projectId), request, auth.getName(), admin(auth)));
    }

    @PutMapping(ApiPaths.GLOSSARY + "/{id}")
    public ApiResponse<GlossaryTermDto> update(@PathVariable String id, @RequestBody GlossaryTermRequest request,
                                               Authentication auth) {
        return ApiResponse.ok(glossary.update(id, request, auth.getName(), admin(auth)));
    }

    @DeleteMapping(ApiPaths.GLOSSARY + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        glossary.delete(id, auth.getName(), admin(auth));
        return ApiResponse.ok(null);
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
