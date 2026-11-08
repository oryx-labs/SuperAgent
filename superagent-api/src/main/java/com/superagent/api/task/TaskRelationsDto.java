package com.superagent.api.task;

import java.math.BigDecimal;
import java.util.List;

/**
 * How a task stands to others: the complex task it is part of, the task it carries on from, the ones that carry on
 * from it, and its own subtasks. {@code totalSpend} is its cost with its subtasks'.
 */
public record TaskRelationsDto(TaskDto parent, TaskDto continuesFrom, List<TaskDto> followUps, List<TaskDto> subtasks,
                               BigDecimal totalSpend) {
}
