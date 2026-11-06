package com.superagent.agent.memory;

import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.MessageRole;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The memory spec and the rules around it, kept free of I/O. The spec is the first version of
 * "what an agent remembers" and is meant to be tuned: it asks for only what will still matter
 * later — when nothing qualifies, the answer is {@link #NO_CHANGE} and the memory stays as it was.
 */
final class AgentMemoryRules {

    /** The model's answer when this conversation adds nothing worth remembering. */
    static final String NO_CHANGE = "NO_CHANGE";
    static final int MAX_MEMORY_CHARS = 3000;
    /** What a person may write by hand; the model keeps to {@link #MAX_MEMORY_CHARS}. */
    static final int MAX_CHARS = 10_000;
    private static final int MAX_TRANSCRIPT_CHARS = 40_000;
    private static final int MAX_MESSAGE_CHARS = 2_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** Outcome of a consolidation run. {@code memory} is set only for {@link Kind#UPDATED}. */
    record Outcome(Kind kind, String memory) {
        enum Kind { UPDATED, NO_CHANGE, INVALID }
    }

    /**
     * Distil when the conversation has settled (no new message for {@code settle}, so a live exchange isn't
     * cut in half) and there is enough of it: {@code minMessages} new ones, or any once it's been idle {@code idle}.
     */
    static boolean due(List<ChatMessage> fresh, Instant now, int minMessages, Duration settle, Duration idle) {
        if (fresh.isEmpty()) {
            return false;
        }
        Instant last = fresh.get(fresh.size() - 1).createdAt();
        if (last.plus(settle).isAfter(now)) {
            return false;
        }
        return fresh.size() >= minMessages || !last.plus(idle).isAfter(now);
    }

    /** Oldest first, most recent kept when it doesn't all fit. */
    static String transcript(List<ChatMessage> messages, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            String body = m.content() == null ? "" : m.content().strip();
            if (body.isEmpty()) {
                continue;
            }
            if (body.length() > MAX_MESSAGE_CHARS) {
                body = body.substring(0, MAX_MESSAGE_CHARS) + "…";
            }
            String who = m.role() == MessageRole.USER ? "用户" : m.role() == MessageRole.ASSISTANT ? "助手" : "系统";
            String line = "[" + STAMP.format(m.createdAt().atZone(zone)) + "] " + who + "：" + body;
            if (total + line.length() > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            lines.add(0, line);
            total += line.length();
        }
        return String.join("\n\n", lines);
    }

    static String prompt(String agentName, String currentMemory, String transcript) {
        return """
                你负责维护 Agent「%s」的长期记忆。下面是它现有的记忆，以及它和用户之间新发生的对话。
                请判断新对话里有没有**以后仍然有用**的信息，并据此更新记忆。

                ## 记什么（宁缺毋滥）
                - 只记以后的对话真正用得上的：用户的身份背景、偏好与习惯、长期目标与项目、已做出的决定和约定、
                  进行中的事项与待办、需要记住的关键事实（名称、数字、链接、结论）。
                - 不记：寒暄客套、一次性的问答、已经结束且不再相关的细节、可以随时重新查到的通用知识、
                  对话过程本身（「用户问了…助手答了…」）。
                - 不确定有没有价值的，不记。

                ## 怎么写
                - 输出完整的新记忆（Markdown），只用下面这些小节，没有内容的小节整节省略：
                  `## 关于用户` / `## 目标与项目` / `## 决定与约定` / `## 进行中与待办` / `## 关键事实`
                - 每条一行、用短句，写结论不写过程；合并重复，过时或被推翻的直接删掉，新旧冲突以新的为准。
                - 已完成的待办移除（若结论有长期价值，写进「决定与约定」或「关键事实」）。
                - 全文不超过 %d 字；超了就进一步压缩、删掉价值最低的条目。
                - 不要编造对话里没有的内容。

                ## 输出要求
                - 如果新对话里**没有任何值得长期记住的新信息**，只输出 %s，不要输出别的。
                - 否则只输出新的记忆全文：第一行是 `# %s 的记忆`，不要任何解释、前言或代码块标记。

                ## 现有记忆
                %s

                ## 新对话
                %s
                """.formatted(agentName, MAX_MEMORY_CHARS, NO_CHANGE, agentName,
                currentMemory == null || currentMemory.isBlank() ? "（暂无）" : currentMemory.strip(), transcript);
    }

    /** Reads the model's answer; anything that isn't a memory document or NO_CHANGE is rejected, never saved. */
    static Outcome parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) { // models sometimes fence the document despite being told not to
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").strip();
        }
        if (text.equals(NO_CHANGE) || (text.length() < 40 && text.contains(NO_CHANGE))) {
            return new Outcome(Outcome.Kind.NO_CHANGE, null);
        }
        if (!text.startsWith("# ") || text.lines().count() < 2) {
            return new Outcome(Outcome.Kind.INVALID, null);
        }
        return new Outcome(Outcome.Kind.UPDATED, text + "\n");
    }

    private AgentMemoryRules() {
    }
}
