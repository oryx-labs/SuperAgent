package com.superagent.web.usage;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.usage.UsageDtos;
import com.superagent.cost.usage.TokenUsageService;
import com.superagent.task.TaskService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Token usage: a task's, the caller's own, and (admins) everyone's. */
@RestController
public class UsageController {

    private final TokenUsageService usage;
    private final TaskService tasks;

    public UsageController(TokenUsageService usage, TaskService tasks) {
        this.usage = usage;
        this.tasks = tasks;
    }

    @GetMapping(ApiPaths.TASKS + "/{id}/usage")
    public ApiResponse<UsageDtos.Task> task(@PathVariable String id, Authentication auth) {
        tasks.status(id, auth.getName()); // only the task's own creator: anyone else gets "not found"
        return ApiResponse.ok(usage.forTask(id));
    }

    /** {@code period}: "month" (this calendar month) or a number of days, today included. */
    @GetMapping(ApiPaths.USAGE)
    public ApiResponse<UsageDtos.Summary> mine(@RequestParam(defaultValue = "month") String period,
                                              @RequestParam(required = false) String tz, Authentication auth) {
        return ApiResponse.ok(summary(auth.getName(), period, tz));
    }

    @GetMapping(ApiPaths.ADMIN_USAGE)
    public ApiResponse<UsageDtos.Summary> everyone(@RequestParam(defaultValue = "month") String period,
                                                  @RequestParam(required = false) String tz) {
        return ApiResponse.ok(summary(null, period, tz));
    }

    /** In the viewer's time zone: this month from its first day, or the last N whole days, today included. */
    private UsageDtos.Summary summary(String user, String period, String tz) {
        ZoneId zone;
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate start;
        if ("month".equals(period)) {
            start = today.withDayOfMonth(1);
        } else {
            int days;
            try {
                days = Integer.parseInt(period);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("period is \"month\" or a number of days: " + period);
            }
            start = today.minusDays(Math.max(1, Math.min(days, 366)) - 1L);
        }
        Instant from = start.atStartOfDay(zone).toInstant();
        return usage.summary(user, from, Instant.now().plus(1, ChronoUnit.MINUTES), zone);
    }
}
