package com.superagent.web.chat;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.chat.CreateSessionRequest;
import com.superagent.api.chat.EditMessageRequest;
import com.superagent.api.chat.RenameSessionRequest;
import com.superagent.api.chat.SendMessageRequest;
import com.superagent.api.chat.SessionDetailDto;
import com.superagent.api.chat.SessionDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Chat endpoints. Session/message management uses the standard
 * {@link ApiResponse} envelope; sending a message streams the agent's reply back
 * as Server-Sent Events.
 */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(ApiPaths.SESSIONS)
    public ApiResponse<SessionDto> createSession(@RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.ok(chatService.createSession(request));
    }

    @GetMapping(ApiPaths.SESSIONS)
    public ApiResponse<List<SessionDto>> listSessions() {
        return ApiResponse.ok(chatService.listSessions());
    }

    @GetMapping(ApiPaths.SESSIONS + "/{id}")
    public ApiResponse<SessionDetailDto> getSession(@PathVariable String id) {
        return ApiResponse.ok(chatService.getSession(id));
    }

    @PutMapping(ApiPaths.SESSIONS + "/{id}/title")
    public ApiResponse<SessionDto> rename(@PathVariable String id, @RequestBody RenameSessionRequest request) {
        return ApiResponse.ok(chatService.renameSession(id, request.title()));
    }

    @DeleteMapping(ApiPaths.SESSIONS + "/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable String id) {
        chatService.deleteSession(id);
        return ApiResponse.empty();
    }

    @PostMapping(value = ApiPaths.SESSIONS + "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable String id, @RequestBody SendMessageRequest request) {
        return chatService.sendMessage(id, request.content());
    }

    @PostMapping(ApiPaths.SESSIONS + "/{id}/stop")
    public ApiResponse<Void> stop(@PathVariable String id) {
        chatService.stopGeneration(id);
        return ApiResponse.empty();
    }

    @PutMapping(ApiPaths.SESSIONS + "/{id}/messages/{messageId}")
    public ApiResponse<Void> editMessage(@PathVariable String id, @PathVariable String messageId,
                                         @RequestBody EditMessageRequest request) {
        chatService.editMessage(id, messageId, request.content());
        return ApiResponse.empty();
    }
}
