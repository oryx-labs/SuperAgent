package com.superagent.agent.memory;

import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryRulesTest {

    private static final Instant T0 = Instant.parse("2026-11-05T02:00:00Z");
    private static final Duration SETTLE = Duration.ofMinutes(2);
    private static final Duration IDLE = Duration.ofMinutes(30);

    private static List<ChatMessage> messages(int count, int lastMinute) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ChatMessage("m" + i, "s", i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                        "msg " + i, T0.plus(Duration.ofMinutes(lastMinute - (count - 1 - i)))))
                .toList();
    }

    @Test
    void distilsOnlySettledConversationsWithEnoughToSay() {
        List<ChatMessage> busy = messages(12, 0);
        assertThat(AgentMemoryRules.due(busy, T0.plus(Duration.ofMinutes(1)), 10, SETTLE, IDLE)).isFalse(); // still talking
        assertThat(AgentMemoryRules.due(busy, T0.plus(Duration.ofMinutes(3)), 10, SETTLE, IDLE)).isTrue();

        List<ChatMessage> few = messages(3, 0);
        assertThat(AgentMemoryRules.due(few, T0.plus(Duration.ofMinutes(5)), 10, SETTLE, IDLE)).isFalse();
        assertThat(AgentMemoryRules.due(few, T0.plus(Duration.ofMinutes(31)), 10, SETTLE, IDLE)).isTrue(); // gone idle
        assertThat(AgentMemoryRules.due(List.of(), T0.plus(Duration.ofDays(1)), 10, SETTLE, IDLE)).isFalse();
    }

    @Test
    void nothingWorthKeepingLeavesMemoryAloneAndOnlyAMemoryDocumentIsEverSaved() {
        assertThat(AgentMemoryRules.parse("NO_CHANGE").kind()).isEqualTo(AgentMemoryRules.Outcome.Kind.NO_CHANGE);
        assertThat(AgentMemoryRules.parse("  NO_CHANGE。 ").kind()).isEqualTo(AgentMemoryRules.Outcome.Kind.NO_CHANGE);

        AgentMemoryRules.Outcome fenced = AgentMemoryRules.parse("```markdown\n# Ada 的记忆\n## 关于用户\n- 偏好简洁\n```");
        assertThat(fenced.kind()).isEqualTo(AgentMemoryRules.Outcome.Kind.UPDATED);
        assertThat(fenced.memory()).startsWith("# Ada 的记忆").contains("- 偏好简洁").doesNotContain("```");

        // An apology, an error or a chatty preamble must never replace the memory file.
        assertThat(AgentMemoryRules.parse("抱歉，我无法完成这个请求。").kind()).isEqualTo(AgentMemoryRules.Outcome.Kind.INVALID);
        assertThat(AgentMemoryRules.parse("").kind()).isEqualTo(AgentMemoryRules.Outcome.Kind.INVALID);
    }

    @Test
    void theTranscriptIsStampedOldestFirst() {
        String transcript = AgentMemoryRules.transcript(messages(2, 0), ZoneId.of("Asia/Shanghai"));
        assertThat(transcript).startsWith("[2026-11-05 09:59] 用户：msg 0").contains("助手：msg 1");
    }

    @Test
    void aNewSessionReadsWholeMessagesUpToItsBudgetAndOnlyTheOldestThatDoesNotFitIsShortened() {
        Instant t = Instant.parse("2026-11-09T06:58:00Z");
        String outline = "大纲开头" + "课".repeat(9_600) + "第16节结尾";
        List<ChatMessage> chat = List.of(
                new ChatMessage("old", "s", MessageRole.USER, "很早的话", t.minusSeconds(600)),
                new ChatMessage("outline", "s", MessageRole.USER, outline, t),
                new ChatMessage("ok1", "s", MessageRole.USER, "确认", t.plusSeconds(30)),
                new ChatMessage("done", "s", MessageRole.ASSISTANT, "任务完成了", t.plusSeconds(60)));
        ZoneId zone = ZoneId.of("Asia/Shanghai");

        // Room for everything: the ~9,600-character outline comes over whole, not cut at 2,000.
        String all = AgentMemoryRules.carryOver(chat, zone, 60_000);
        assertThat(all).contains(outline, "很早的话").endsWith("助手：任务完成了");
        assertThat(all.indexOf("很早的话")).isLessThan(all.indexOf("大纲开头"));

        // Too little room: the newest come first; the outline keeps its start and end and says how to read it all,
        // and nothing older than it is carried.
        String tight = AgentMemoryRules.carryOver(chat, zone, 5_000);
        assertThat(tight).contains("大纲开头", "第16节结尾", "中间省略", "chat_history_read 读取消息 outline", "用户：确认")
                .doesNotContain("很早的话");
    }
}
