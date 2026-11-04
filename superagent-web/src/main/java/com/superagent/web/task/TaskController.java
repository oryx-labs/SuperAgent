package com.superagent.web.task;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.chat.SendMessageRequest;
import com.superagent.api.task.ArtifactDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskAgentOptionDto;
import com.superagent.api.task.TaskDetailDto;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskEventDto;
import com.superagent.core.chat.AgentType;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.web.provider.ProviderService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    public TaskController(TaskService taskService, ProviderService providerService) {
        this.taskService = taskService;
        this.providerService = providerService;
    }

    @PostMapping(ApiPaths.TASKS)
    public ApiResponse<TaskDto> create(@RequestBody CreateTaskRequest request) {
        return ApiResponse.ok(taskService.create(request));
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

    @GetMapping(ApiPaths.TASKS)
    public ApiResponse<List<TaskDto>> list() {
        return ApiResponse.ok(taskService.list());
    }

    @GetMapping(ApiPaths.TASKS + "/{id}")
    public ApiResponse<TaskDetailDto> detail(@PathVariable String id) {
        return ApiResponse.ok(taskService.detail(id));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/events")
    public ApiResponse<List<TaskEventDto>> events(@PathVariable String id,
                                                  @RequestParam(defaultValue = "0") int offset) {
        return ApiResponse.ok(taskService.eventsFrom(id, offset));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/status")
    public ApiResponse<TaskDto> status(@PathVariable String id) {
        return ApiResponse.ok(taskService.status(id));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/artifacts")
    public ApiResponse<List<ArtifactDto>> artifacts(@PathVariable String id) {
        return ApiResponse.ok(taskService.artifacts(id));
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/artifacts/{name}")
    public ResponseEntity<byte[]> downloadArtifact(@PathVariable String id, @PathVariable String name) {
        byte[] content = taskService.readArtifact(id, name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + sanitize(name) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\"\\r\\n]", "_");
    }

    @PostMapping(ApiPaths.TASKS + "/{id}/messages")
    public ApiResponse<Void> sendMessage(@PathVariable String id, @RequestBody SendMessageRequest request) {
        taskService.sendMessage(id, request.content());
        return ApiResponse.empty();
    }

    @PostMapping(ApiPaths.TASKS + "/{id}/stop")
    public ApiResponse<Void> stop(@PathVariable String id) {
        taskService.stop(id);
        return ApiResponse.empty();
    }

    @DeleteMapping(ApiPaths.TASKS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        taskService.delete(id);
        return ApiResponse.empty();
    }
}
