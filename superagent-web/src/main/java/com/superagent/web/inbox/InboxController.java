package com.superagent.web.inbox;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.inbox.InboxEventDto;
import com.superagent.api.inbox.InboxSummaryDto;
import com.superagent.api.inbox.InboxTodoDto;
import com.superagent.event.UserInboxService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The signed-in user's outside messages and the to-dos made from them; the owner is always the authenticated user. */
@RestController
public class InboxController {

    private final UserInboxService inbox;

    public InboxController(UserInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping(ApiPaths.INBOX + "/summary")
    public ApiResponse<InboxSummaryDto> summary(Authentication auth) {
        return ApiResponse.ok(inbox.summary(auth.getName()));
    }

    @GetMapping(ApiPaths.INBOX + "/events")
    public ApiResponse<List<InboxEventDto>> events(@RequestParam(defaultValue = "200") int limit, Authentication auth) {
        return ApiResponse.ok(inbox.events(auth.getName(), limit));
    }

    @GetMapping(ApiPaths.INBOX + "/todos")
    public ApiResponse<List<InboxTodoDto>> todos(Authentication auth) {
        return ApiResponse.ok(inbox.todos(auth.getName()));
    }

    @PutMapping(ApiPaths.INBOX + "/todos/{id}")
    public ApiResponse<InboxTodoDto> setStatus(@PathVariable String id, @RequestBody Map<String, String> body,
                                               Authentication auth) {
        return ApiResponse.ok(inbox.setTodoStatus(auth.getName(), id, body.get("status")));
    }

    @PostMapping(ApiPaths.INBOX + "/todos/{id}/task")
    public ApiResponse<InboxTodoDto> toTask(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(inbox.toTask(auth.getName(), id, null));
    }
}
