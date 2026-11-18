package com.superagent.agent.chat;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.project.ProjectAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A chat turn's system prompt carries the agent's project context, and nothing of a project it isn't in. */
class ChatProjectContextTest {

    private static Agent agent(String id) {
        return new Agent(id, id, null, null, "你是数据分析师", null, AgentStatus.ACTIVE, "ada", AgentKind.STANDARD, null,
                Instant.now(), Instant.now());
    }

    @Test
    void onlyAnAgentInAProjectGetsItsContext() {
        ProjectAccess projects = mock(ProjectAccess.class);
        when(projects.context("in", null)).thenReturn(Optional.of("## 项目共同上下文（项目「看板」）\n约定：金额两位小数"));
        when(projects.context("out", null)).thenReturn(Optional.empty());

        assertThat(ChatService.characterAndProject(agent("in"), projects)).contains("项目共同上下文", "金额两位小数");
        assertThat(ChatService.characterAndProject(agent("out"), projects)).doesNotContain("项目共同上下文");
        assertThat(ChatService.characterAndProject(agent("in"), null)).doesNotContain("项目共同上下文"); // projects not wired
    }
}
