package com.superagent.api.task;

import com.superagent.api.chat.MessageDto;

import java.util.List;

/**
 * Full task view: summary, the spec {@code brief}, the steering messages, the
 * replayed execution event stream (both keyed by the task id in shared agent-run
 * storage), and the structured flow {@code phases} (empty for single-pass tasks).
 */
public record TaskDetailDto(
        TaskDto task,
        String brief,
        List<MessageDto> messages,
        List<TaskEventDto> events,
        List<TaskPhaseDto> phases,
        /** A task run by a built-in harness (coding): its steps, workspace, checks and review; null otherwise. */
        HarnessDto harness) {

    public TaskDetailDto(TaskDto task, String brief, List<MessageDto> messages, List<TaskEventDto> events,
                         List<TaskPhaseDto> phases) {
        this(task, brief, messages, events, phases, null);
    }
}
