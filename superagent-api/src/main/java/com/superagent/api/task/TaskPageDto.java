package com.superagent.api.task;

import java.math.BigDecimal;
import java.util.List;

/**
 * One page of the task list, newest first. {@code page} is 1-based; {@code total} counts every task that matches the
 * filter, and {@code spend} is what they cost together (a complex task with its subtasks).
 */
public record TaskPageDto(List<TaskDto> items, int total, int page, int size, BigDecimal spend) {
}
