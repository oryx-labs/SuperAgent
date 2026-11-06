package com.superagent.web.skill;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.skill.SkillDto;
import com.superagent.api.skill.SkillImageUpload;
import com.superagent.api.skill.SkillRequest;
import com.superagent.skills.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Shared skills: every signed-in user can read, create and edit; delete is creator- or admin-only. */
@RestController
public class SkillController {

    private final SkillService skills;

    public SkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping(ApiPaths.SKILLS)
    public ApiResponse<List<SkillDto>> list(Authentication auth) {
        return ApiResponse.ok(skills.list(auth.getName()));
    }

    @PostMapping(ApiPaths.SKILLS)
    public ApiResponse<SkillDto> create(@RequestBody SkillRequest request, Authentication auth) {
        return ApiResponse.ok(skills.create(request, auth.getName()));
    }

    @GetMapping(ApiPaths.SKILLS + "/{id}")
    public ApiResponse<SkillDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(skills.get(id, auth.getName()));
    }

    @PutMapping(ApiPaths.SKILLS + "/{id}")
    public ApiResponse<SkillDto> update(@PathVariable String id, @RequestBody SkillRequest request, Authentication auth) {
        return ApiResponse.ok(skills.update(id, request, auth.getName()));
    }

    @DeleteMapping(ApiPaths.SKILLS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        skills.delete(id, auth.getName());
        return ApiResponse.empty();
    }

    @PostMapping(ApiPaths.SKILLS + "/{id}/images")
    public ApiResponse<SkillDto> addImage(@PathVariable String id, @RequestBody SkillImageUpload upload, Authentication auth) {
        return ApiResponse.ok(skills.addImage(id, upload, auth.getName()));
    }

    @GetMapping(ApiPaths.SKILLS + "/{id}/images/{name}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String name, Authentication auth) {
        return skills.readImage(id, name, auth.getName()).toResponse();
    }

    @DeleteMapping(ApiPaths.SKILLS + "/{id}/images/{name}")
    public ApiResponse<SkillDto> deleteImage(@PathVariable String id, @PathVariable String name, Authentication auth) {
        return ApiResponse.ok(skills.deleteImage(id, name, auth.getName()));
    }
}
