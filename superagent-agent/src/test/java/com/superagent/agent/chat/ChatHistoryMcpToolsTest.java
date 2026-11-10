package com.superagent.agent.chat;

import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.MessageRole;
import com.superagent.runtime.mcp.McpToolException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An agent finds what was said earlier in its conversation, and reads a long message whole, page by page. */
class ChatHistoryMcpToolsTest {

    private final Instant t = Instant.parse("2026-11-09T06:58:00Z");
    private final String outline = "大纲｜实战营 " + "x".repeat(25_000) + " 01｜第一节标题";
    private final List<ChatMessage> chat = List.of(
            new ChatMessage("m1", "s1", MessageRole.USER, outline, t),
            new ChatMessage("m2", "s2", MessageRole.ASSISTANT, "收到大纲", t.plusSeconds(10)));

    @Test
    void searchFindsAcrossSessionsAndReadPagesThroughALongMessage() {
        assertThat(ChatHistoryMcpTools.search(chat, "实战营", 10)).contains("m1", "用户", "共 " + outline.length() + " 字")
                .doesNotContain("m2");
        assertThat(ChatHistoryMcpTools.search(chat, null, 10)).contains("m1", "m2"); // no query: the latest
        assertThat(ChatHistoryMcpTools.search(chat, "不存在", 10)).contains("没有找到");

        String first = ChatHistoryMcpTools.read(chat, "m1", 0);
        assertThat(first).contains("大纲｜实战营", "offset=20000").doesNotContain("第一节标题");
        assertThat(ChatHistoryMcpTools.read(chat, "m1", 20_000)).contains("01｜第一节标题");
        assertThatThrownBy(() -> ChatHistoryMcpTools.read(chat, "nope", 0)).isInstanceOf(McpToolException.class);
    }
}
