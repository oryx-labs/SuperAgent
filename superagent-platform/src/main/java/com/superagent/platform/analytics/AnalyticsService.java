package com.superagent.platform.analytics;

import com.superagent.api.analytics.AnalyticsDto;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.automation.Automation;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-wide figures for the admin "Analytics" page, computed from the stores on request (the data is
 * small enough; a reporting store can replace this when it isn't). Days are in the server's time zone.
 */
@Service
public class AnalyticsService {

    private static final int ACTIVE_DAYS = 7;

    private final UserStore users;
    private final AgentStore agents;
    private final AutomationStore automations;
    private final TaskStore tasks;

    public AnalyticsService(UserStore users, AgentStore agents, AutomationStore automations, TaskStore tasks) {
        this.users = users;
        this.agents = agents;
        this.automations = automations;
        this.tasks = tasks;
    }

    public AnalyticsDto overview(int days) {
        int span = Math.max(7, Math.min(days, 90));
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        List<User> allUsers = users.listUsers();
        List<Automation> allAutomations = automations.list();
        List<Task> live = tasks.listTasks().stream().filter(t -> t.status() != TaskStatus.DELETED).toList();

        Instant activeSince = now.minus(Duration.ofDays(ACTIVE_DAYS));
        int active = (int) live.stream().filter(t -> t.createdAt().isAfter(activeSince))
                .map(Task::createdBy).filter(Objects::nonNull).distinct().count();
        int done = (int) live.stream().filter(t -> t.status() == TaskStatus.DONE).count();
        int failed = (int) live.stream().filter(t -> t.status() == TaskStatus.ERROR).count();

        Map<LocalDate, int[]> byDay = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(zone);
        for (int i = span - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i), new int[3]);
        }
        Map<String, ModelTotals> byModel = new LinkedHashMap<>();
        BigDecimal spend = BigDecimal.ZERO;
        for (Task t : live) {
            int[] created = byDay.get(LocalDate.ofInstant(t.createdAt(), zone));
            if (created != null) {
                created[0]++;
            }
            if (t.finishedAt() != null && (t.status() == TaskStatus.DONE || t.status() == TaskStatus.ERROR)) {
                int[] finished = byDay.get(LocalDate.ofInstant(t.finishedAt(), zone));
                if (finished != null) {
                    finished[t.status() == TaskStatus.DONE ? 1 : 2]++;
                }
            }
            BigDecimal cost = t.spend() == null ? BigDecimal.ZERO : t.spend();
            spend = spend.add(cost);
            byModel.computeIfAbsent(t.model() == null ? "" : t.model(), k -> new ModelTotals()).add(cost);
        }

        List<AnalyticsDto.Day> daily = new ArrayList<>();
        byDay.forEach((d, c) -> daily.add(new AnalyticsDto.Day(d, c[0], c[1], c[2])));
        List<AnalyticsDto.ModelUsage> models = byModel.entrySet().stream()
                .map(e -> new AnalyticsDto.ModelUsage(e.getKey().isEmpty() ? null : e.getKey(), e.getValue().tasks,
                        e.getValue().spend))
                .sorted(Comparator.comparingInt(AnalyticsDto.ModelUsage::tasks).reversed())
                .toList();
        return new AnalyticsDto(allUsers.size(), (int) allUsers.stream().filter(User::enabled).count(), active,
                ACTIVE_DAYS, (int) agents.list().stream().filter(a -> a.status() != AgentStatus.DELETED).count(),
                allAutomations.size(), (int) allAutomations.stream().filter(Automation::enabled).count(),
                live.size(), done, failed, done + failed == 0 ? null : (double) done / (done + failed), spend,
                daily, models);
    }

    private static final class ModelTotals {
        int tasks;
        BigDecimal spend = BigDecimal.ZERO;

        void add(BigDecimal cost) {
            tasks++;
            spend = spend.add(cost);
        }
    }
}
