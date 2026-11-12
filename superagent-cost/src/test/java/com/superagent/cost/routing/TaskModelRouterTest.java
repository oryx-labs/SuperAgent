package com.superagent.cost.routing;

import com.superagent.core.chat.AgentType;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskModelChoice;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.cost.ModelPriceService;
import com.superagent.cost.ModelPriceService.Candidate;
import com.superagent.platform.provider.ModelCall;
import com.superagent.platform.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The default model judges each task and picks the cheapest model able to do it; a failed task moves to the next
 * dearer model, step by step up to the default model.
 */
class TaskModelRouterTest {

    private final ModelPriceService prices = mock(ModelPriceService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final ModelCall call = mock(ModelCall.class);
    private final TaskModelRouter router = new TaskModelRouter(prices, settings, call);

    /** Cheapest first, as the price service lists them; "gpt-big" is the dearest but kept out of tasks. */
    private final List<Candidate> models = List.of(
            model("haiku", 0.8, 4.0, true), model("glm", 1.0, 4.0, true), model("sonnet", 3.0, 15.0, true),
            model("opus", 5.0, 25.0, true), model("gpt-big", 15.0, 75.0, false));

    TaskModelRouterTest() {
        when(prices.candidates()).thenReturn(models);
        when(settings.agentDefaultModel()).thenReturn("opus");
        when(prices.chatModels()).thenReturn(List.of("opus", "gpt-big"));
    }

    @Test
    void theDefaultModelJudgesAndOthersStepInWhenItCant() {
        when(call.ask(eq("opus"), anyString(), anyLong(), any())).thenThrow(new IllegalStateException("quota"));
        when(call.ask(eq("gpt-big"), anyString(), anyLong(), any())).thenReturn(
                "好的：{\"difficulty\":\"medium\",\"model\":\"glm\",\"reason\":\"常规文档，glm 够用且便宜\"}");

        TaskModelChoice c = router.choose(task("写周报", "sonnet")).orElseThrow();

        // The default model couldn't; the dearest other could judge (it just can't run tasks itself).
        assertThat(c).extracting(TaskModelChoice::by, TaskModelChoice::judge, TaskModelChoice::difficulty, TaskModelChoice::model)
                .containsExactly(TaskModelChoice.BY_JUDGE, "gpt-big", "medium", "glm");
        assertThat(c.candidates()).contains("haiku | $0.8 / $4", "opus | $5 / $25").doesNotContain("gpt-big");

        // A model tasks can't use: by difficulty instead (hard: the default model).
        when(call.ask(eq("opus"), anyString(), anyLong(), any())).thenReturn("{\"difficulty\":\"hard\",\"model\":\"gpt-big\",\"reason\":\"难\"}");
        assertThat(router.choose(task("重构", "sonnet")).orElseThrow().model()).isEqualTo("opus");

        // Nobody can judge: the default model, and the record says why.
        when(call.ask(anyString(), anyString(), anyLong(), any())).thenReturn("说不好");
        TaskModelChoice none = router.choose(task("调研", "sonnet")).orElseThrow();
        assertThat(none.by()).isEqualTo(TaskModelChoice.BY_FALLBACK);
        assertThat(none.model()).isEqualTo("opus");
        assertThat(none.reasoning()).contains("没能判断", "默认模型");
    }

    @Test
    void fixedModelsRunCheapestFirstWithoutJudgingAndFailRatherThanFallBack() {
        // gpt-big is off for tasks and "gone" can't run: both skipped.
        when(settings.taskFixedModels()).thenReturn(List.of("sonnet", "gpt-big", "glm", "gone"));

        TaskModelChoice first = router.choose(task("写周报", "opus")).orElseThrow();
        assertThat(first).extracting(TaskModelChoice::by, TaskModelChoice::model, TaskModelChoice::difficulty)
                .containsExactly(TaskModelChoice.BY_FIXED, "glm", null);
        assertThat(first.reasoning()).contains("gpt-big", "gone");
        assertThat(router.strongest(task("复杂任务", "opus")).orElseThrow().model()).isEqualTo("glm");
        org.mockito.Mockito.verifyNoInteractions(call);

        assertThat(router.escalate(task("x", "glm"), "boom").orElseThrow().model()).isEqualTo("sonnet");
        TaskModelChoice out = router.escalate(task("x", "sonnet"), "boom").orElseThrow();
        assertThat(out.refused()).isTrue();
        assertThat(out.reasoning()).contains("没有更贵的");

        when(settings.taskFixedModels()).thenReturn(List.of("gpt-big"));
        TaskModelChoice none = router.choose(task("x", "opus")).orElseThrow();
        assertThat(none.refused()).isTrue();
        assertThat(none.reasoning()).contains("都用不了");
    }

    @Test
    void aFailedTaskMovesUpOneModelAtATimeUpToTheDefault() {
        assertThat(router.escalate(task("x", "haiku"), "boom").orElseThrow().model()).isEqualTo("glm");
        assertThat(router.escalate(task("x", "glm"), "boom").orElseThrow().model()).isEqualTo("sonnet");
        TaskModelChoice last = router.escalate(task("x", "sonnet"), "boom").orElseThrow();
        assertThat(last.model()).isEqualTo("opus");
        assertThat(last.reasoning()).contains("默认模型");
        assertThat(router.escalate(task("x", "opus"), "boom")).isEmpty(); // past the default model: no further
    }

    private static Candidate model(String id, double in, double out, boolean forTasks) {
        return new Candidate(id, "claude", in, out, null, null, "2026-05-01", "anthropic", false, forTasks);
    }

    private static Task task(String title, String model) {
        return new Task("t1", title, "说明", TaskSource.WEB_CHAT, null, null, "general", TaskStatus.PENDING, 0,
                BigDecimal.ZERO, model, AgentType.CLAUDE, null, "ada", null, Instant.now(), null, null, Instant.now(), null);
    }
}
