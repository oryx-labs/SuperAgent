package com.superagent.agent.memory;

import com.superagent.agent.log.AgentLogService;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMemoryState;
import com.superagent.core.agent.AgentMemoryStateStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** An agent writes its memory by category; distilling folds new conversation in without wiping what it wrote. */
class AgentMemoryWriteTest {

    private static final class InMemoryStates implements AgentMemoryStateStore {
        final Map<String, AgentMemoryState> rows = new HashMap<>();
        @Override public Optional<AgentMemoryState> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public AgentMemoryState save(AgentMemoryState s) { rows.put(s.agentId(), s); return s; }
        @Override public void delete(String id) { rows.remove(id); }
    }

    private final InMemoryStates states = new InMemoryStates();
    private String modelAnswer;
    private String lastPrompt;
    private final AgentMemoryService memory = new AgentMemoryService(null, null, states, null, null, List.of(),
            10, 2, 30, 3, "Asia/Shanghai", 10, 10, mock(AgentLogService.class)) {
        @Override
        String summarize(Agent agent, String prompt) {
            lastPrompt = prompt;
            return modelAnswer;
        }
    };
    private final Agent agent = new Agent("a1", "Ada", null, null, null, null, AgentStatus.ACTIVE, "alice",
            AgentKind.STANDARD, null, Instant.now(), Instant.now());

    private void distill(String answer) {
        modelAnswer = answer;
        memory.consolidate(agent, List.of(new ChatMessage("m1", "s1", MessageRole.USER, "随便聊聊", Instant.now())));
    }

    @Test
    void appendAddsToACategoryAndReplaceRewritesOnlyThatOne() {
        memory.writeSection(agent, "项目理解", "- 看板项目给 A 客户", false, "alice", "s1", null);
        memory.writeSection(agent, "项目理解", "- 十二月上线", false, "alice", "s1", null);
        memory.writeSection(agent, "踩过的坑", "- H2 不支持 X", false, "bob", null, "t1");
        assertThat(memory.read(agent)).isEqualTo("""
                # Ada 的记忆

                ## 项目理解
                - 看板项目给 A 客户
                - 十二月上线

                ## 踩过的坑
                - H2 不支持 X
                """);

        memory.writeSection(agent, "项目理解", "- 一月上线", true, "alice", "s2", null);
        assertThat(memory.read(agent)).contains("## 项目理解\n- 一月上线\n\n## 踩过的坑\n- H2 不支持 X")
                .doesNotContain("十二月");
        assertThat(memory.agentSections(agent)).containsExactly("项目理解", "踩过的坑");
        assertThat(memory.lastAgentWrite(agent)).extracting("writtenBy", "writtenSession", "writtenTask")
                .containsExactly("alice", "s2", null);

        assertThatThrownBy(() -> memory.writeSection(agent, " ", "x", false, "alice", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // The owner's own edit is theirs: nothing is held for the agent any more.
        memory.edit(agent, "## 用户偏好\n- 中文", "alice");
        assertThat(memory.agentSections(agent)).isEmpty();
    }

    @Test
    void distillingKeepsWhatTheAgentWroteAndOnlyAddsToIt() {
        memory.writeSection(agent, "工作约定", "- 提交信息用英文", false, "alice", "s1", null);
        memory.writeSection(agent, "用户偏好", "- 回答简短", false, "alice", "s1", null);

        // The model rewrites the agent's category, drops another, and brings a new one.
        distill("""
                # Ada 的记忆

                ## 工作约定
                - 每次提交前跑测试

                ## 踩过的坑
                - 18080 不能随便重启
                """);
        String after = memory.read(agent);
        assertThat(after).contains("## 工作约定\n- 提交信息用英文\n- 每次提交前跑测试", "## 用户偏好\n- 回答简短",
                "## 踩过的坑\n- 18080 不能随便重启");
        assertThat(lastPrompt).contains("工作约定、用户偏好", "不要删改");

        // A category the model empties goes, unless the agent wrote it.
        distill("# Ada 的记忆\n\n## 踩过的坑\n\n## 用户偏好\n");
        assertThat(memory.read(agent)).doesNotContain("踩过的坑").contains("- 回答简短", "- 提交信息用英文");
    }

    @Test
    void anOldUncategorisedMemoryIsSortedWithoutLosingALine() {
        states.save(new AgentMemoryState("a1", "alice", null, null, 0, """
                # Ada 的记忆
                用户叫 Alice，在杭州
                ## 关于用户
                - 喜欢简短回答
                ## 决定与约定
                - 金额保留两位
                ## 关键事实
                - 仓库在 /code/api
                """, Instant.now()));

        modelAnswer = "NO_CHANGE";
        memory.consolidate(agent, List.of(new ChatMessage("m1", "s1", MessageRole.USER, "嗯", Instant.now())));
        // The model was shown it already sorted.
        assertThat(lastPrompt).contains("## 用户偏好\n- 喜欢简短回答", "## 工作约定\n- 金额保留两位", "## 其他\n用户叫 Alice，在杭州");

        // A model that forgets a category loses nothing; the first write sorts it too.
        distill("# Ada 的记忆\n\n## 用户偏好\n- 喜欢简短回答\n- 用中文\n");
        memory.writeSection(agent, "项目理解", "- 看板", false, "alice", "s1", null);
        assertThat(memory.read(agent)).contains("用户叫 Alice，在杭州", "- 喜欢简短回答", "- 用中文", "- 金额保留两位",
                "- 仓库在 /code/api", "## 项目理解\n- 看板").doesNotContain("## 关于用户", "## 决定与约定");
    }
}
