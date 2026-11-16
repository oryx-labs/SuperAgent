package com.superagent.agent.chat;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * An agent's chat talks, its tasks do the work. A task an agent set off from its chat reports back there: when it
 * finishes, fails or needs the user's decision, a message goes into the agent's conversation, and the agent's
 * next turn is told (its CLI conversation never saw it). A task the SuperAgent hands a member does the same in the
 * member's chat (it took the task on, then how it went); the SuperAgent itself hears through its background.
 */
@Component
public class ChatTaskResults {

    /** Author of a task's report in the chat: this prefix plus the task id. */
    public static final String AUTHOR_PREFIX = "task:";

    /** Joins an agent's system prompt when it has the task tools. */
    static final String GUIDE = TaskConfirmPrompt.rules("create_task", "")
            + "任务由你自己执行（带着你的人设、记忆和技能）。完成、失败或需要确认时，结果会自动发到这个对话里（标着「任务通知」），用户可能接着它说。\n";

    /** Put right before every user message, next to the request: the system prompt alone loses to the wording. */
    static final String TURN_CHECK = TaskConfirmPrompt.turnCheck("create_task");

    private static final Logger log = LoggerFactory.getLogger(ChatTaskResults.class);
    private static final int RESULT_CHARS = 1500;

    private final ChatStore chats;
    private final AgentStore agents;
    private final TaskAgentStore taskAgents;

    ChatTaskResults(ChatStore chats, AgentStore agents, TaskAgentStore taskAgents) {
        this.chats = chats;
        this.agents = agents;
        this.taskAgents = taskAgents;
    }

    @EventListener
    public void onTaskStatus(NoticeEvents.TaskStatusChanged e) {
        String text = report(e);
        if (text == null) {
            return;
        }
        TaskOrigin origin = taskAgents.origin(e.taskId()).filter(o -> o.sessionId() != null).orElse(null);
        Agent agent = origin == null ? null : agents.find(origin.agentId()).filter(a -> !a.isSuper()).orElse(null);
        if (agent == null) {
            return;
        }
        // Into the conversation the user sees now: the agent may have moved to a new session since.
        String sessionId = chats.listSessionsByAgent(agent.id()).stream()
                .filter(s -> s.status() != SessionStatus.DELETED)
                .max(Comparator.comparing(ChatSession::createdAt)).map(ChatSession::id).orElse(origin.sessionId());
        chats.appendMessage(new ChatMessage(UUID.randomUUID().toString(), sessionId, MessageRole.ASSISTANT, text,
                Instant.now(), AUTHOR_PREFIX + e.taskId()));
        log.info("task {} ({}) reported back to agent {}'s chat", e.taskId(), e.status(), agent.id());
    }

    private static String report(NoticeEvents.TaskStatusChanged e) {
        String title = "「" + e.title() + "」";
        return switch (e.status()) {
            case "done" -> "任务" + title + "完成了。" + (e.result() == null || e.result().isBlank() ? ""
                    : "\n\n" + clip(e.result().strip()));
            case "error" -> "任务" + title + "失败了" + (e.error() == null || e.error().isBlank() ? "。" : "：" + clip(e.error().strip()));
            case "needs_confirmation" -> "任务" + title + "在等你确认，打开任务看它的问题并回复。";
            default -> null; // pending, running, stopped (the user stopped it), deleted
        };
    }

    private static String clip(String s) {
        return s.length() <= RESULT_CHARS ? s : s.substring(0, RESULT_CHARS) + "…（完整结果见任务详情）";
    }

    /** The turn's prompt for an agent with the task tools: task reports since its last reply, then the check. */
    String prompt(String sessionId, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        List<String> posts = postsSinceLastReply(sessionId);
        if (!posts.isEmpty()) {
            sb.append("## 你的任务刚才发到对话里的消息（接到的任务、完成的结果，按时间顺序）\n");
            posts.forEach(p -> sb.append("- ").append(p.replace("\n", "\n  ")).append("\n"));
            sb.append("\n");
        }
        return sb.append(TURN_CHECK).append("\n## 用户说\n").append(userPrompt).toString();
    }

    /** Task reports posted since the agent last answered (the newest user message itself is skipped), oldest first. */
    List<String> postsSinceLastReply(String sessionId) {
        List<ChatMessage> messages = chats.listMessages(sessionId);
        List<String> posts = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.role() == MessageRole.USER) {
                continue;
            }
            if (m.role() != MessageRole.ASSISTANT || m.author() == null || !m.author().startsWith(AUTHOR_PREFIX)) {
                break;
            }
            posts.add(0, m.content());
        }
        return posts;
    }
}
