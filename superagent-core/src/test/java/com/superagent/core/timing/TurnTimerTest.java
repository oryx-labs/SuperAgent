package com.superagent.core.timing;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TurnTimerTest {

    private final AtomicLong now = new AtomicLong(1_000);

    private void at(long ms) {
        now.set(ms);
    }

    private static AgentEvent ev(AgentEventType type, String payload) {
        return new AgentEvent("s", null, 0, type, payload, Instant.now());
    }

    @Test
    void stagesAddUpToTheTurnAndAFailedAttemptIsRetryTime() {
        TurnTimer timer = new TurnTimer(now::get);
        AgentEventSink sink = timer.tapTools(e -> { });
        at(1_500);
        timer.attemptStarted();          // prep 500
        at(2_000);
        sink.emit(ev(AgentEventType.ERROR, "401"));
        at(2_100);
        timer.attemptEnded();
        at(2_200);
        timer.attemptStarted();          // the failed attempt: 700 of retry
        at(5_200);
        sink.emit(ev(AgentEventType.SYSTEM, "id")); // startup 3000
        at(9_200);
        sink.emit(ev(AgentEventType.THINKING, "")); // model 4000
        at(10_000);
        sink.emit(ev(AgentEventType.TOOL_USE, "Bash"));
        at(13_000);
        sink.emit(ev(AgentEventType.TOOL_RESULT, "ok")); // tool 3000
        at(13_500);
        sink.emit(ev(AgentEventType.TOOL_USE, "Bash"));
        at(14_500);
        sink.emit(ev(AgentEventType.TOOL_RESULT, "ok")); // tool 1000
        at(16_200);
        sink.emit(ev(AgentEventType.RESULT, "done"));
        timer.attemptEnded();
        at(16_500);
        TurnTiming t = timer.finish("s", "m", null); // save 300

        assertThat(t.prepMs()).isEqualTo(500);
        assertThat(t.retryMs()).isEqualTo(700);
        assertThat(t.startupMs()).isEqualTo(3_000);
        assertThat(t.modelMs()).isEqualTo(4_000);
        assertThat(t.toolsMs()).isEqualTo(4_000);
        assertThat(t.outputMs()).isEqualTo(3_000); // 7000 after the first output, minus the tools
        assertThat(t.saveMs()).isEqualTo(300);
        assertThat(t.attempts()).isEqualTo(2);
        assertThat(t.tools()).containsExactly(new TurnTiming.ToolTime("Bash", 2, 4_000));
        assertThat(t.prepMs() + t.retryMs() + t.startupMs() + t.modelMs() + t.toolsMs() + t.outputMs() + t.saveMs())
                .isEqualTo(t.totalMs());
    }

    @Test
    void aTurnThatNeverReachedTheCliIsAllPrep() {
        TurnTimer timer = new TurnTimer(now::get);
        at(1_800);
        TurnTiming t = timer.finish("s", "m", null);
        assertThat(t.prepMs()).isEqualTo(800);
        assertThat(t.attempts()).isZero();
    }
}
