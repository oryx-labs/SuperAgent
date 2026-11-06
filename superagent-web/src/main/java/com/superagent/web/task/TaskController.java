package com.superagent.web.task;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.chat.SendMessageRequest;
import com.superagent.api.knowledge.KnowledgeEntryDto;
import com.superagent.api.knowledge.KnowledgeRequests;
import com.superagent.api.task.ArtifactDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskAgentOptionDto;
import com.superagent.api.task.TaskDetailDto;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskEventDto;
import com.superagent.api.task.TaskPageDto;
import com.superagent.api.task.TaskStatsDto;
import com.superagent.core.chat.AgentType;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.knowledge.KnowledgeService;
import com.superagent.platform.provider.ProviderService;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import com.superagent.task.TaskService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Task endpoints: create (which starts background execution), list/detail,
 * incremental event polling, steering (answer a confirmation / follow up), stop,
 * and soft delete. All use the standard {@link ApiResponse} envelope.
 */
@RestController
public class TaskController {

    private final TaskService taskService;
    private final ProviderService providerService;
    private final ImageAttachmentStore images;
    private final KnowledgeService knowledge;

    public TaskController(TaskService taskService, ProviderService providerService, ImageAttachmentStore images,
                          KnowledgeService knowledge) {
        this.taskService = taskService;
        this.providerService = providerService;
        this.images = images;
        this.knowledge = knowledge;
    }

    @PostMapping(ApiPaths.TASKS)
    public ApiResponse<TaskDto> create(@RequestBody CreateTaskRequest request, Authentication auth) {
        return ApiResponse.ok(taskService.create(request, auth == null ? null : auth.getName()));
    }

    /** Agent CLIs a task can be created with (those with a runner here), and each one's models. */
    @GetMapping(ApiPaths.TASKS + "/agents")
    public ApiResponse<List<TaskAgentOptionDto>> agents() {
        return ApiResponse.ok(taskService.runnableAgents().stream()
                .sorted(Comparator.comparingInt(AgentType::ordinal))
                .map(a -> new TaskAgentOptionDto(a.cliName(),
                        ProviderCatalog.find(a.cliName()).map(ProviderCatalog.Entry::name).orElse(a.cliName()),
                        providerService.storedModels(a.cliName())))
                .toList());
    }

    /** The signed-in user's own tasks; {@code status}: all | active | a task status. */
    @GetMapping(ApiPaths.TASKS)
    public ApiResponse<TaskPageDto> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String status,
                                         Authentication auth) {
        return ApiResponse.ok(taskService.page(auth.getName(), status, page, size));
    }

    @GetMapping(ApiPaths.TASKS + "/stats")
    public ApiResponse<TaskStatsDto> stats(Authentication auth) {
        return ApiResponse.ok(taskService.stats(auth.getName()));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}")
    public ApiResponse<TaskDetailDto> detail(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(taskService.detail(id, auth.getName()));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/events")
    public ApiResponse<List<TaskEventDto>> events(@PathVariable String id,
                                                  @RequestParam(defaultValue = "0") int offset, Authentication auth) {
        return ApiResponse.ok(taskService.eventsFrom(id, auth.getName(), offset));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/status")
    public ApiResponse<TaskDto> status(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(taskService.status(id, auth.getName()));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/artifacts")
    public ApiResponse<List<ArtifactDto>> artifacts(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(taskService.artifacts(id, auth.getName()));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/artifacts/{name}")
    public ResponseEntity<byte[]> downloadArtifact(@PathVariable String id, @PathVariable String name,
                                                   Authentication auth) {
        byte[] content = taskService.readArtifact(id, auth.getName(), name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + sanitize(name) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /** Manual "save to knowledge base": copies an artifact into the signed-in user's own knowledge base. */
    @PostMapping(ApiPaths.TASKS + "/{id}/artifacts/{name}/knowledge")
    public ApiResponse<KnowledgeEntryDto> saveArtifactToKnowledge(@PathVariable String id, @PathVariable String name,
                                                                  @RequestBody KnowledgeRequests.SaveArtifact request,
                                                                  Authentication auth) {
        String folder = request.folder() == null ? "" : request.folder().strip().replaceAll("^/+|/+$", "");
        return ApiResponse.ok(knowledge.saveFile(auth.getName(), taskService.artifactFile(id, auth.getName(), name), folder, name, false));
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\"\\r\\n]", "_");
    }

    @PostMapping(ApiPaths.TASKS + "/{id}/messages")
    public ApiResponse<Void> sendMessage(@PathVariable String id, @RequestBody SendMessageRequest request,
                                         Authentication auth) {
        taskService.sendMessage(id, auth.getName(), request.content(), request.images(), request.fileIds());
        return ApiResponse.empty();
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/messages/{messageId}/images/{index}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String messageId,
                                        @PathVariable int index, Authentication auth) {
        taskService.status(id, auth.getName()); // someone else's task (and its images) doesn't exist for you
        return images.read(id, messageId, index).toResponse();
    }

    @PostMapping(ApiPaths.TASKS + "/{id}/stop")
    public ApiResponse<Void> stop(@PathVariable String id, Authentication auth) {
        taskService.stop(id, auth.getName());
        return ApiResponse.empty();
    }

    @DeleteMapping(ApiPaths.TASKS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        taskService.delete(id, auth.getName());
        return ApiResponse.empty();
    }
}
