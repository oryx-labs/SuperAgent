package com.superagent.orchestrator;

import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuperAgentInboxTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final Duration QUIET = Duration.ofSeconds(10);
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);

    private static ChatMessage msg(MessageRole role, int secondsAfterT0) {
        return new ChatMessage("m" + secondsAfterT0, "s", role, "x", T0.plusSeconds(secondsAfterT0));
    }

    @Test
    void onlyInputAfterTheCursorIsPendingAndTheAgentsOwnRepliesNeverAre() {
        List<ChatMessage> all = List.of(msg(MessageRole.USER, 0), msg(MessageRole.ASSISTANT, 5),
                msg(MessageRole.USER, 20), msg(MessageRole.SYSTEM, 25), msg(MessageRole.ASSISTANT, 30));

        assertThat(SuperAgentInbox.pending(all, T0.plusSeconds(5))).extracting(ChatMessage::id)
                .containsExactly("m20", "m25"); // a task report (system) counts as input too
        assertThat(SuperAgentInbox.pending(all, null)).hasSize(3);
    }

    @Test
    void aRoundWaitsForQuietButNeverLongerThanMaxWait() {
        List<ChatMessage> burst = List.of(msg(MessageRole.USER, 0), msg(MessageRole.USER, 4));

        assertThat(SuperAgentInbox.due(burst, T0.plusSeconds(8), QUIET, MAX_WAIT, false)).isFalse(); // still typing
        assertThat(SuperAgentInbox.due(burst, T0.plusSeconds(14), QUIET, MAX_WAIT, false)).isTrue();  // 10s quiet
        assertThat(SuperAgentInbox.due(burst, T0.plusSeconds(1), QUIET, MAX_WAIT, true)).isTrue();    // plan approved

        // Someone who keeps sending every few seconds still gets a round once the oldest has waited max-wait.
        List<ChatMessage> stream = List.of(msg(MessageRole.USER, 0), msg(MessageRole.USER, 58));
        assertThat(SuperAgentInbox.due(stream, T0.plusSeconds(60), QUIET, MAX_WAIT, false)).isTrue();
        assertThat(SuperAgentInbox.due(List.of(), T0.plusSeconds(600), QUIET, MAX_WAIT, true)).isFalse();
    }

    @Test
    void assignedTasksAreReportedOncePerChangeAndDroppedWhenSettled() {
        assertThat(SuperAgentInbox.watch(TaskStatus.RUNNING, null)).isEqualTo(SuperAgentInbox.Watch.KEEP);
        // A question is reported once and still watched, since the task can go on to finish.
        assertThat(SuperAgentInbox.watch(TaskStatus.NEEDS_CONFIRMATION, null))
                .isEqualTo(SuperAgentInbox.Watch.REPORT_AND_KEEP);
        assertThat(SuperAgentInbox.watch(TaskStatus.NEEDS_CONFIRMATION, "NEEDS_CONFIRMATION"))
                .isEqualTo(SuperAgentInbox.Watch.KEEP);
        assertThat(SuperAgentInbox.watch(TaskStatus.DONE, "NEEDS_CONFIRMATION"))
                .isEqualTo(SuperAgentInbox.Watch.REPORT_AND_DROP);
        assertThat(SuperAgentInbox.watch(TaskStatus.DELETED, null)).isEqualTo(SuperAgentInbox.Watch.REPORT_AND_DROP);
    }
}
