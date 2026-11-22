package com.superagent.agent.chat;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskOrigin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A task an agent set off from its chat reports back there, and the agent's next turn is told. */
class ChatTaskResultsTest {

    private final ChatStore chats = mock(ChatStore.class);
    private final AgentStore agents = mock(AgentStore.class);
    private final TaskAgentStore taskAgents = mock(TaskAgentStore.class);
    private final ChatTaskResults results = new ChatTaskResults(chats, agents, taskAgents);

    @Test
    void aFinishedTaskReportsIntoTheAgentsCurrentConversationAndTheNextTurnSeesIt() {
        when(taskAgents.origin("t1")).thenReturn(Optional.of(new TaskOrigin("t1", "dev", "old", "m1")));
        when(agents.find("dev")).thenReturn(Optional.of(agent("dev", AgentKind.STANDARD)));
        // The agent moved to a new session (model change) after setting the task off.
        when(chats.listSessionsByAgent("dev")).thenReturn(List.of(session("old", 1), session("new", 2)));

        results.onTaskStatus(new NoticeEvents.TaskStatusChanged("t1", "ada", "竞品分析", "web_chat", "running", null, null, null));
        verify(chats, never()).appendMessage(any());
        results.onTaskStatus(new NoticeEvents.TaskStatusChanged("t1", "ada", "竞品分析", "web_chat", "done", null, null, "报告在 Files/竞品.md"));

        ArgumentCaptor<ChatMessage> posted = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chats).appendMessage(posted.capture());
        assertThat(posted.getValue().sessionId()).isEqualTo("new");
        assertThat(posted.getValue().author()).isEqualTo("task:t1");
        assertThat(posted.getValue().content()).contains("竞品分析", "完成", "Files/竞品.md");

        List<ChatMessage> history = new ArrayList<>(List.of(
                msg(MessageRole.ASSISTANT, "已开任务「竞品分析」", null), posted.getValue(), msg(MessageRole.USER, "好的，接着说", "ada")));
        when(chats.listMessages("new")).thenReturn(history);
        assertThat(results.prompt("new", "好的，接着说")).contains("（任务 t1）", "Files/竞品.md", "回复前先判断",
                "## 用户说\n好的，接着说").doesNotContain("已开任务");
    }

    @Test
    void aTaskWaitingOnTheUserBringsItsQuestionAndAMilestoneIsToldToo() {
        when(taskAgents.origin("t3")).thenReturn(Optional.of(new TaskOrigin("t3", "dev", "s", "m")));
        when(agents.find("dev")).thenReturn(Optional.of(agent("dev", AgentKind.STANDARD)));
        when(chats.listSessionsByAgent("dev")).thenReturn(List.of(session("s", 1)));

        results.onTaskNote(new NoticeEvents.TaskNote("t3", "ada", "加登录", "验证第 1 轮没通过，正在自动修复"));
        results.onTaskStatus(new NoticeEvents.TaskStatusChanged("t3", "ada", "加登录", "web_chat", "needs_confirmation",
                null, null, "规格写好了，请确认。\n\n👉 下一步：回复「确认规格」开始开发"));
        // Stopping again on the same question is not news: the chat isn't told twice.
        results.onTaskStatus(new NoticeEvents.TaskStatusChanged("t3", "ada", "加登录", "web_chat", "needs_confirmation",
                null, null, null, true));

        ArgumentCaptor<ChatMessage> posted = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chats, org.mockito.Mockito.times(2)).appendMessage(posted.capture());
        assertThat(posted.getAllValues()).allMatch(m -> "task:t3".equals(m.author()));
        assertThat(posted.getAllValues().get(0).content()).contains("加登录", "正在自动修复");
        assertThat(posted.getAllValues().get(1).content()).contains("在等你确认", "回复「确认规格」");
    }

    @Test
    void aFailureThatWillBeRetriedIsNotReportedButTheFinalOneIs() {
        when(taskAgents.origin("t4")).thenReturn(Optional.of(new TaskOrigin("t4", "dev", "s", "m1")));
        when(agents.find("dev")).thenReturn(Optional.of(agent("dev", AgentKind.STANDARD)));
        when(chats.listSessionsByAgent("dev")).thenReturn(List.of(session("s", 1)));

        results.onTaskStatus(new NoticeEvents.TaskStatusChanged("t4", "ada", "x", "web_chat", "error", "boom", null, null, false, true));
        verify(chats, never()).appendMessage(any());
        results.onTaskStatus(new NoticeEvents.TaskStatusChanged("t4", "ada", "x", "web_chat", "error", "模型 m，已重试 9 次；最后错误：boom", null, null));
        verify(chats).appendMessage(any());
    }

    @Test
    void theSuperAgentsTasksReportThroughItsBackgroundNotHere() {
        when(taskAgents.origin("t2")).thenReturn(Optional.of(new TaskOrigin("t2", "sa", "s", "m")));
        when(agents.find("sa")).thenReturn(Optional.of(agent("sa", AgentKind.SUPER)));
        results.onTaskStatus(new NoticeEvents.TaskStatusChanged("t2", "ada", "x", "web_chat", "error", "boom", null, null));
        verify(chats, never()).appendMessage(any());
    }

    private static Agent agent(String id, AgentKind kind) {
        return new Agent(id, id, null, null, null, null, AgentStatus.ACTIVE, "ada", kind, null, Instant.now(), Instant.now(), null);
    }

    private static ChatSession session(String id, long at) {
        return new ChatSession(id, id, AgentType.CLAUDE, null, null, "dev", SessionStatus.ACTIVE,
                Instant.ofEpochSecond(at), Instant.ofEpochSecond(at));
    }

    private static ChatMessage msg(MessageRole role, String content, String author) {
        return new ChatMessage(java.util.UUID.randomUUID().toString(), "new", role, content, Instant.now(), author);
    }
}
