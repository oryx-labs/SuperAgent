package com.superagent.agent.chat;

import com.superagent.api.chat.StreamEventDto;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every viewer of a shared agent sees the turn the sender sees, however late they open it. */
class LiveTurnTest {

    /** Records the events it is sent, by name. */
    private static final class Recorder extends SseEmitter {
        final List<String> sent = new ArrayList<>();
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().stream().map(d -> d.getData()).filter(StreamEventDto.class::isInstance)
                    .map(StreamEventDto.class::cast).forEach(e -> sent.add(e.type() + ":" + e.payload()));
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    @Test
    void aLateViewerGetsTheTurnSoFarThenTheRest() {
        ChatService.LiveTurn turn = new ChatService.LiveTurn("alice", "Alice", Instant.now());
        turn.publish(new StreamEventDto("thinking", 1, "想一"));
        turn.publish(new StreamEventDto("thinking", 2, "想"));
        turn.publish(new StreamEventDto("tool_use", 3, "Read"));
        turn.publish(new StreamEventDto("text", 4, "你"));

        Recorder bob = new Recorder();
        turn.watch(bob);
        turn.publish(new StreamEventDto("text", 5, "好"));
        turn.publish(new StreamEventDto("done", -1, "你好"));
        turn.end();

        // Deltas of the same kind are merged in the replay; what follows arrives as it happens.
        assertThat(bob.sent).containsExactly("thinking:想一想", "tool_use:Read", "text:你", "text:好", "done:你好");
        assertThat(bob.completed).isTrue();
        assertThat(turn.toDto("s1").text()).isEqualTo("你好");
        assertThat(turn.toDto("s1").activity()).isEqualTo("Read");

        Recorder carol = new Recorder(); // opens the page just after the turn ended: the whole turn, then closed
        turn.watch(carol);
        assertThat(carol.sent).containsExactly("thinking:想一想", "tool_use:Read", "text:你好", "done:你好");
        assertThat(carol.completed).isTrue();
    }

    @Test
    void aShutdownEndsTheReplyForTheSenderAndEveryViewerWithOneInterruptedEvent() {
        ChatService.LiveTurn turn = new ChatService.LiveTurn("alice", "Alice", Instant.now());
        Recorder alice = new Recorder();
        turn.sender(alice);
        Recorder bob = new Recorder();
        turn.watch(bob);
        turn.publish(new StreamEventDto("text", 1, "写到"));

        turn.interrupt();
        turn.publish(new StreamEventDto("stopped", -1, "写到")); // what the cancelled turn still says is dropped
        turn.publish(new StreamEventDto("done", -1, "写到"));

        assertThat(alice.sent).containsExactly("interrupted:server_restart");
        assertThat(alice.completed).isTrue();
        assertThat(bob.sent).containsExactly("text:写到", "interrupted:server_restart");
        assertThat(bob.sent).noneMatch(e -> e.startsWith("error"));
        assertThat(bob.completed).isTrue();
        assertThat(turn.interrupted()).isTrue();
    }
}
