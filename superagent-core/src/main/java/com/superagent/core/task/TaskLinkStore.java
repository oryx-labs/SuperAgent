package com.superagent.core.task;

import java.util.List;
import java.util.Optional;

/** Subtasks and follow-ups; a link is written once, when its task is made. */
public interface TaskLinkStore {

    void save(TaskLink link);

    Optional<TaskLink> find(String taskId);

    /** Every link, for views that need many at once (the list hiding subtasks, say). */
    List<TaskLink> all();

    default List<String> children(String parentId) {
        return all().stream().filter(l -> parentId.equals(l.parentId()))
                .sorted(java.util.Comparator.comparing(TaskLink::createdAt)).map(TaskLink::taskId).toList();
    }

    /** The tasks that carry on from this one. */
    default List<String> followUps(String taskId) {
        return all().stream().filter(l -> taskId.equals(l.continuesFrom()))
                .sorted(java.util.Comparator.comparing(TaskLink::createdAt)).map(TaskLink::taskId).toList();
    }
}
