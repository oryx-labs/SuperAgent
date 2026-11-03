package com.superagent.api.task;

import com.superagent.api.chat.MessageDto;

import java.util.List;

/**
 * Full task view: summary, the spec {@code brief}, the steering messages, and the
 * replayed execution event stream (both keyed by the task id in shared agent-run
 * storage).
 */
public record TaskDetailDto(
        TaskDto task,
        String brief,
        List<MessageDto> messages,
        List<TaskEventDto> events) {
}
