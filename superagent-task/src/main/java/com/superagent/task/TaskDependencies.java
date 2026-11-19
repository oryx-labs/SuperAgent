package com.superagent.task;

import com.superagent.api.task.TaskRefDto;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskDependency;
import com.superagent.core.task.TaskDependencyStore;
import com.superagent.core.task.TaskStatus;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * "Start after": a task waits until every task it depends on is done. One that was deleted no longer holds it back;
 * one that failed for good or was stopped does, and its owner is told once, so they can sort it out or run it anyway.
 */
final class TaskDependencies {

    private final TaskDependencyStore store;
    private final ApplicationEventPublisher events; // null in tests

    TaskDependencies(TaskDependencyStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    /** The tasks a new one waits for: those named in {@code after}, and the one it carries on from. */
    static List<String> named(List<String> after, String continuesFrom) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : after == null ? List.<String>of() : after) {
            if (id != null && !id.isBlank()) {
                ids.add(id.strip());
            }
        }
        if (continuesFrom != null) {
            ids.add(continuesFrom);
        }
        return List.copyOf(ids);
    }

    void record(String taskId, List<String> dependsOn, Instant now) {
        dependsOn.forEach(d -> store.save(new TaskDependency(taskId, d, now, null)));
    }

    List<TaskDependency> of(String taskId) {
        return store.of(taskId);
    }

    Map<String, List<TaskDependency>> byTask() {
        Map<String, List<TaskDependency>> out = new HashMap<>();
        store.all().forEach(d -> out.computeIfAbsent(d.taskId(), k -> new ArrayList<>()).add(d));
        return out;
    }

    void clear(String taskId) {
        store.clear(taskId);
    }

    /**
     * Where a task stands on what it waits for: the tasks not done yet, and the first of them that failed for good or
     * was stopped (null if none). {@code finished}: whether a task is over, as its complex task would count it.
     */
    record Wait(List<Task> pending, Task blocker, TaskDependency blockedBy) {
        boolean ready() {
            return pending.isEmpty();
        }
    }

    static Wait wait(List<TaskDependency> deps, Function<String, Task> find, Predicate<Task> finished) {
        List<Task> pending = new ArrayList<>();
        Task blocker = null;
        TaskDependency blockedBy = null;
        for (TaskDependency d : deps == null ? List.<TaskDependency>of() : deps) {
            Task t = find.apply(d.dependsOn());
            if (t == null || t.status() == TaskStatus.DELETED || t.status() == TaskStatus.DONE) {
                continue;
            }
            pending.add(t);
            if (blocker == null && finished.test(t)) {
                blocker = t;
                blockedBy = d;
            }
        }
        return new Wait(pending, blocker, blockedBy);
    }

    /**
     * The waiting tasks that may start. A task held back by one that failed or was stopped stays put, and its owner
     * hears it once (the note goes back to the chat that set it off). Rows no longer needed are dropped on the way, so
     * the table only ever holds tasks that haven't started: theirs once they start, are stopped or deleted, and rows
     * older than {@code orphanBefore} whose task was never saved (a creation that failed part-way).
     */
    List<Task> ready(List<Task> waiting, Map<String, Task> byId, Predicate<Task> finished,
                     java.util.function.UnaryOperator<Instant> orphanBefore) {
        Map<String, List<TaskDependency>> deps = byTask();
        java.util.Set<String> stillWaiting = new java.util.HashSet<>();
        waiting.forEach(t -> stillWaiting.add(t.id()));
        Instant cutoff = orphanBefore.apply(Instant.now());
        deps.forEach((taskId, rows) -> {
            Task t = byId.get(taskId);
            boolean orphan = t == null && rows.stream().allMatch(d -> d.createdAt().isBefore(cutoff));
            if (orphan || (t != null && !stillWaiting.contains(taskId))) {
                store.clear(taskId);
            }
        });
        List<Task> out = new ArrayList<>();
        for (Task t : waiting) {
            Wait w = wait(deps.get(t.id()), byId::get, finished);
            if (w.ready()) {
                out.add(t);
            } else if (w.blocker() != null && w.blockedBy().notifiedAt() == null) {
                store.markNotified(t.id(), w.blocker().id(), Instant.now());
                if (events != null) {
                    events.publishEvent(new NoticeEvents.TaskNote(t.id(), t.createdBy(), t.title(),
                            "它要等的任务「" + w.blocker().title() + "」（" + w.blocker().id() + "）" + outcome(w.blocker())
                                    + "，所以这个任务先不开始。处理好前面的任务后它会自动开始；不想等的话，在任务详情点「直接跑」，"
                                    + "或让我用 run_task_now 直接开始。"));
                }
            }
        }
        return out;
    }

    /** Why a task can't start on its own, for its views; null when nothing it waits for failed. */
    static String blocked(Wait w) {
        return w.blocker() == null ? null : "「" + w.blocker().title() + "」" + outcome(w.blocker());
    }

    static List<TaskRefDto> refs(Wait w) {
        return w.pending().stream().map(t -> new TaskRefDto(t.id(), t.title(), t.status().name().toLowerCase())).toList();
    }

    private static String outcome(Task t) {
        return t.status() == TaskStatus.STOPPED ? "已被停止"
                : "失败了" + (t.error() == null || t.error().isBlank() ? "" : "（" + abbreviate(t.error()) + "）");
    }

    private static String abbreviate(String s) {
        String one = s.strip().replaceAll("\\s+", " ");
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
