package com.superagent.agent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A failed turn keeps its error in the stored reply, so the chat still shows it after a reload. */
class ChatReplyErrorTest {

    @Test
    void theErrorStaysInTheReplyUnlessTheTurnWasStopped() {
        assertThat(ChatService.storedReply("", "Credit balance is too low", false))
                .isEqualTo(ChatService.ERROR_MARK + "Credit balance is too low");
        assertThat(ChatService.storedReply("写到一半", "connection reset", false))
                .isEqualTo("写到一半\n\n" + ChatService.ERROR_MARK + "connection reset");
        assertThat(ChatService.storedReply("写到一半", "cancelled", true)).isEqualTo("写到一半");
        assertThat(ChatService.storedReply("好的", null, false)).isEqualTo("好的");
    }
}
