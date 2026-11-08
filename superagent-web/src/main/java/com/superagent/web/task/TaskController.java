package com.superagent.web.task;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.chat.SendMessageRequest;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskAgentOptionDto;
import com.superagent.api.task.TaskDetailDto;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskEventDto;
import com.superagent.api.task.TaskPageDto;
import com.superagent.api.task.TaskStatsDto;
import com.superagent.core.chat.AgentType;
import com.superagent.core.provider.ProviderCatalog;
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
import org.springframework.web.bind.annotation.PutMapping;
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

    public TaskController(TaskService taskService, ProviderService providerService, ImageAttachmentStore images) {
        this.taskService = taskService;
        this.providerService = providerService;
        this.images = images;
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

    /**
     * The signed-in user's own tasks; {@code status}: all | active | a task status; {@code tag}, {@code agent} and
     * {@code source} narrow it further. Subtasks are listed under their complex task, not here.
     */
    @GetMapping(ApiPaths.TASKS)
    public ApiResponse<TaskPageDto> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String tag,
                                         @RequestParam(required = false) String agent,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) String project,
                                         Authentication auth) {
        return ApiResponse.ok(taskService.page(auth.getName(), status, tag, agent, source, project, page, size));
    }

    /** What the user's tasks can be filtered by (tags, agents, sources), each with its count and cost. */
    @GetMapping(ApiPaths.TASKS + "/facets")
    public ApiResponse<com.superagent.api.task.TaskFacetsDto> facets(Authentication auth) {
        return ApiResponse.ok(taskService.facets(auth.getName()));
    }

    /** The complex task it's part of, its subtasks, and the tasks it carries on from or that carry on from it. */
    @GetMapping(ApiPaths.TASKS + "/{id}/relations")
    public ApiResponse<com.superagent.api.task.TaskRelationsDto> relations(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(taskService.relations(id, auth.getName()));
    }

    @PutMapping(ApiPaths.TASKS + "/{id}/tags")
    public ApiResponse<TaskDto> tags(@PathVariable String id, @RequestBody java.util.Map<String, java.util.List<String>> body,
                                     Authentication auth) {
        return ApiResponse.ok(taskService.setTags(id, auth.getName(), body.get("tags")));
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

    @GetMapping(ApiPaths.TASKS + "/{id}/model-choices")
    public ApiResponse<List<com.superagent.api.task.TaskModelChoiceDto>> modelChoices(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(taskService.modelChoices(id, auth.getName()));
    }

        /** Where the task's time went: waiting to run, each turn's stages, waiting for replies. */
    @GetMapping(ApiPaths.TASKS + "/{id}/timing")
    public ApiResponse<com.superagent.api.timing.TaskTimingDto> timing(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(taskService.timing(id, auth.getName()));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/status")
    public ApiResponse<TaskDto> status(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(taskService.status(id, auth.getName()));
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
