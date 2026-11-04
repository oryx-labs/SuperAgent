package com.superagent.api.task;

import java.util.List;

/** One page of the task list, newest first. {@code page} is 1-based; {@code total} counts every task. */
public record TaskPageDto(List<TaskDto> items, int total, int page, int size) {
}
