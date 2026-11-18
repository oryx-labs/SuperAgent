package com.superagent.web.project;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.project.ProjectBriefDto;
import com.superagent.api.project.ProjectDto;
import com.superagent.api.project.ProjectMemoryDto;
import com.superagent.api.project.ProjectRepoDto;
import com.superagent.api.project.ProjectRequests;
import com.superagent.project.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Projects: the user's projects, their people, which project an agent is in, and what its agents share. */
@RestController
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping(ApiPaths.PROJECTS)
    public ApiResponse<List<ProjectDto>> list(Authentication auth) {
        return ApiResponse.ok(projects.list(auth.getName()));
    }

    @PostMapping(ApiPaths.PROJECTS)
    public ApiResponse<ProjectDto> create(@RequestBody ProjectRequests.Save request, Authentication auth) {
        return ApiResponse.ok(projects.create(auth.getName(), request.name(), request.description()));
    }

    @GetMapping(ApiPaths.PROJECTS + "/{id}")
    public ApiResponse<ProjectDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(projects.get(id, auth.getName()));
    }

    @PutMapping(ApiPaths.PROJECTS + "/{id}")
    public ApiResponse<ProjectDto> update(@PathVariable String id, @RequestBody ProjectRequests.Save request, Authentication auth) {
        return ApiResponse.ok(projects.update(id, auth.getName(), request.name(), request.description()));
    }

    @PostMapping(ApiPaths.PROJECTS + "/{id}/archive")
    public ApiResponse<ProjectDto> archive(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(projects.archive(id, auth.getName()));
    }

    @PostMapping(ApiPaths.PROJECTS + "/{id}/reopen")
    public ApiResponse<ProjectDto> reopen(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(projects.reopen(id, auth.getName()));
    }

    @PostMapping(ApiPaths.PROJECTS + "/{id}/members")
    public ApiResponse<ProjectDto> addMembers(@PathVariable String id, @RequestBody ProjectRequests.Members request,
                                              Authentication auth) {
        return ApiResponse.ok(projects.addMembers(id, auth.getName(), request.usernames()));
    }

    @DeleteMapping(ApiPaths.PROJECTS + "/{id}/members/{username}")
    public ApiResponse<ProjectDto> removeMember(@PathVariable String id, @PathVariable String username, Authentication auth) {
        return ApiResponse.ok(projects.removeMember(id, auth.getName(), username));
    }

    @PutMapping(ApiPaths.AGENTS + "/{agentId}/project")
    public ApiResponse<Void> setAgentProject(@PathVariable String agentId, @RequestBody ProjectRequests.AgentProject request,
                                             Authentication auth) {
        projects.setAgentProject(agentId, auth.getName(), request.projectId());
        return ApiResponse.ok(null);
    }

    // --- repositories, brief, memory ---

    @GetMapping(ApiPaths.PROJECTS + "/{id}/repos")
    public ApiResponse<List<ProjectRepoDto>> repos(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(projects.repos(id, auth.getName()));
    }

    @PostMapping(ApiPaths.PROJECTS + "/{id}/repos")
    public ApiResponse<List<ProjectRepoDto>> addRepo(@PathVariable String id, @RequestBody ProjectRequests.Repo request,
                                                     Authentication auth) {
        return ApiResponse.ok(projects.saveRepo(id, auth.getName(), null, request));
    }

    @PutMapping(ApiPaths.PROJECTS + "/{id}/repos/{repoId}")
    public ApiResponse<List<ProjectRepoDto>> updateRepo(@PathVariable String id, @PathVariable String repoId,
                                                        @RequestBody ProjectRequests.Repo request, Authentication auth) {
        return ApiResponse.ok(projects.saveRepo(id, auth.getName(), repoId, request));
    }

    @DeleteMapping(ApiPaths.PROJECTS + "/{id}/repos/{repoId}")
    public ApiResponse<List<ProjectRepoDto>> deleteRepo(@PathVariable String id, @PathVariable String repoId, Authentication auth) {
        return ApiResponse.ok(projects.deleteRepo(id, auth.getName(), repoId));
    }

    @GetMapping(ApiPaths.PROJECTS + "/{id}/brief")
    public ApiResponse<ProjectBriefDto> brief(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(projects.brief(id, auth.getName()));
    }

    @PutMapping(ApiPaths.PROJECTS + "/{id}/brief")
    public ApiResponse<ProjectBriefDto> saveBrief(@PathVariable String id, @RequestBody ProjectRequests.Brief request,
                                                  Authentication auth) {
        return ApiResponse.ok(projects.saveBrief(id, auth.getName(), request));
    }

    @GetMapping(ApiPaths.PROJECTS + "/{id}/memory")
    public ApiResponse<List<ProjectMemoryDto>> memories(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(projects.memories(id, auth.getName()));
    }

    @PostMapping(ApiPaths.PROJECTS + "/{id}/memory")
    public ApiResponse<ProjectMemoryDto> addMemory(@PathVariable String id, @RequestBody ProjectRequests.Memory request,
                                                   Authentication auth) {
        return ApiResponse.ok(projects.addMemory(id, auth.getName(), request));
    }

    @PutMapping(ApiPaths.PROJECTS + "/{id}/memory/{memoryId}")
    public ApiResponse<ProjectMemoryDto> updateMemory(@PathVariable String id, @PathVariable String memoryId,
                                                      @RequestBody ProjectRequests.Memory request, Authentication auth) {
        return ApiResponse.ok(projects.updateMemory(id, memoryId, auth.getName(), request));
    }

    @DeleteMapping(ApiPaths.PROJECTS + "/{id}/memory/{memoryId}")
    public ApiResponse<Void> deleteMemory(@PathVariable String id, @PathVariable String memoryId, Authentication auth) {
        projects.deleteMemory(id, memoryId, auth.getName());
        return ApiResponse.empty();
    }
}
