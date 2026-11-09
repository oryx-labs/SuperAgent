package com.superagent.orchestrator;

import com.superagent.runtime.mcp.McpGrants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The SuperAgent hands work to its team: its chat can manage files and delegate, but not make things itself. */
class SuperAgentDelegatesTest {

    @Test
    void theChatCanDelegateAndManageFilesButNotProduceThem() {
        assertThat(SuperAgentService.CHAT_TOOLS).contains("mcp__superagent__start_task", "mcp__superagent__assign_task", "mcp__superagent__hire_member",
                "mcp__superagent__file_find", "mcp__superagent__file_read", "mcp__superagent__file_delete");
        assertThat(SuperAgentService.CHAT_TOOLS).doesNotContain("mcp__superagent__file_write", "mcp__superagent__file_save");
        assertThat(McpGrants.WORK_TOOLS).contains("mcp__superagent__file_write", "mcp__superagent__file_get")
                .doesNotContain("mcp__superagent__file_delete");
    }

    @Test
    void backgroundRoundsKeepTheirHandsOffFilesAndExternalOnesOffTheTeam() {
        assertThat(SuperAgentService.BACKGROUND_TOOLS).noneMatch(t -> t.contains("__file_") || t.endsWith("start_task"));
        assertThat(SuperAgentService.EXTERNAL_ROUND_TOOLS).doesNotContain("mcp__superagent__assign_task",
                "mcp__superagent__hire_member");
    }

    @Test
    void theChatSetsUpAutomationsButItsBackgroundCannot() {
        assertThat(SuperAgentService.CHAT_TOOLS).contains("mcp__superagent__create_automation", "mcp__superagent__list_automations");
        assertThat(SuperAgentService.BACKGROUND_TOOLS).noneMatch(t -> t.contains("automation"));

        var tools = new SuperAgentAutomationTools(new com.superagent.task.automation.AutomationMcpTools(
                org.mockito.Mockito.mock(com.superagent.task.automation.AutomationService.class)));
        // One get_current_time per run: the SuperAgent's own.
        assertThat(tools.tools()).extracting(t -> t.path("name").asText()).contains("create_automation")
                .doesNotContain("get_current_time");
        var background = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa", null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.call(background, "list_automations",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))
                .isInstanceOf(com.superagent.runtime.mcp.McpToolException.class);
    }

    @Test
    void theChatPromptSaysToDelegate() {
        String prompt = SuperAgentPrompts.chat(6);
        // Simple things it does itself; anything that takes reading, producing or time goes out, with the user's example.
        assertThat(prompt).contains("简单的事你自己做", "拿不准时，按派出去处理", "理解一下，后面会用到", "start_task", "propose_plan",
                "确认执行").doesNotContain("%d");
        assertThat(SuperAgentPrompts.background(6)).contains("propose_plan");
    }

    @Test
    void startingATaskIsForTheChatOnly() throws Exception {
        SuperAgentService service = org.mockito.Mockito.mock(SuperAgentService.class);
        SuperAgentTaskTools tools = new SuperAgentTaskTools(service);
        var args = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"title\":\"做 PPT\",\"brief\":\"按风格做\",\"kind\":\"general\"}");
        var chat = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa", "s", "m", null);
        var round = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa", null, null, null);

        // The reply that starts it ("m") comes along, for the task card under it.
        org.mockito.Mockito.when(service.startTask("ada", null, "做 PPT", "按风格做", "m", "general")).thenReturn("t1");
        assertThat(tools.call(chat, "start_task", args).text()).contains("t1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.call(round, "start_task", args)).hasMessageContaining("聊天");
    }

    @Test
    void aTaskMustSayWhetherItIsCoding() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(com.superagent.task.TaskKinds.require(json.readTree("{\"kind\":\" Coding \"}"))).isEqualTo("coding");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> com.superagent.task.TaskKinds.require(json.readTree("{}")))
                .isInstanceOf(com.superagent.runtime.mcp.McpToolException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> com.superagent.task.TaskKinds.require(json.readTree("{\"kind\":\"writing\"}")))
                .hasMessageContaining("coding、general 或 complex");
    }

    @Test
    void theUserCanSayWhichProjectAndWhichAgentANewMemberBelongsTo() throws Exception {
        SuperAgentService service = org.mockito.Mockito.mock(SuperAgentService.class);
        SuperAgentTools tools = new SuperAgentTools(service, null);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var chat = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa", "s", "m", null);
        com.superagent.core.agent.Agent hired = org.mockito.Mockito.mock(com.superagent.core.agent.Agent.class);
        org.mockito.Mockito.when(hired.id()).thenReturn("a2");
        org.mockito.Mockito.when(hired.name()).thenReturn("小张");
        org.mockito.Mockito.when(service.hire("ada", "小张", "做调研", null, null, java.util.List.of(), "p1", "a1")).thenReturn(hired);
        org.mockito.Mockito.when(service.describePlace("ada", "a2")).thenReturn("项目：增长；上级：组长");

        var hire = json.readTree("{\"name\":\"小张\",\"persona\":\"做调研\",\"project_id\":\"p1\",\"parent_id\":\"a1\"}");
        assertThat(tools.call(chat, "hire_member", hire).text()).contains("小张", "项目：增长；上级：组长");

        // An existing member moves without a plan; "none" takes it out again.
        org.mockito.Mockito.when(service.place("ada", "a2", "none", null)).thenReturn(hired);
        assertThat(tools.call(chat, "place_member", json.readTree("{\"id\":\"a2\",\"project_id\":\"none\"}")).text()).contains("已调整");
        assertThat(tools.call(chat, "place_member", json.readTree("{\"id\":\"a2\"}")).error()).isTrue();
        // A project that isn't the user's comes back as an error the agent can read, not a failure of the round.
        org.mockito.Mockito.when(service.place("ada", "a2", "nope", null))
                .thenThrow(new com.superagent.core.error.NotFoundException("没有这个项目") { });
        var missing = tools.call(chat, "place_member", json.readTree("{\"id\":\"a2\",\"project_id\":\"nope\"}"));
        assertThat(missing.error()).isTrue();
        assertThat(missing.text()).contains("没有这个项目");

        assertThat(SuperAgentService.CHAT_TOOLS).contains("mcp__superagent__place_member", "mcp__superagent__list_projects");
        assertThat(SuperAgentService.EXTERNAL_ROUND_TOOLS).doesNotContain("mcp__superagent__place_member"); // messages can't reorganize the team
    }
}
