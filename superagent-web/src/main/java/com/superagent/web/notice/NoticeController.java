package com.superagent.web.notice;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.notice.NoticeDto;
import com.superagent.api.notice.NoticeStatsDto;
import com.superagent.core.notice.Notice;
import com.superagent.platform.notice.NoticeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;

/** The signed-in user's to-do home page. */
@RestController
public class NoticeController {

    private final NoticeService notices;

    public NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    @GetMapping(ApiPaths.NOTICES)
    public ApiResponse<List<NoticeDto>> list(Authentication auth) {
        return ApiResponse.ok(notices.list(auth.getName()));
    }

    @GetMapping(ApiPaths.NOTICES + "/stats")
    public ApiResponse<NoticeStatsDto> stats(Authentication auth) {
        return ApiResponse.ok(notices.stats(auth.getName()));
    }

    /** Read (finished work, heads-up) or handled (decision, to-do). */
    @PostMapping(ApiPaths.NOTICES + "/{id}/done")
    public ApiResponse<NoticeDto> done(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(notices.markDone(auth.getName(), id));
    }

    @PostMapping(ApiPaths.NOTICES + "/{id}/ignore")
    public ApiResponse<NoticeDto> ignore(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(notices.ignore(auth.getName(), id));
    }

    @PostMapping(ApiPaths.NOTICES + "/{id}/reopen")
    public ApiResponse<NoticeDto> reopen(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(notices.reopen(auth.getName(), id));
    }

    /** Marks all unread finished-work items and heads-ups as read. */
    @PostMapping(ApiPaths.NOTICES + "/read-all")
    public ApiResponse<Integer> readAll(Authentication auth) {
        return ApiResponse.ok(notices.markAllRead(auth.getName(), EnumSet.of(Notice.Kind.DONE, Notice.Kind.NOTICE)));
    }
}
