package com.superagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.task.TaskPhase;
import com.superagent.core.task.TaskPhaseStatus;
import com.superagent.core.task.TaskPhaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The fixed coding flow: PRD → develop → verify, each a resumed turn, with real progress at each
 * boundary. Holds what the flow is (phases, progress points, per-phase prompts) and its persisted
 * state; {@link TaskService} drives it turn by turn.
 */
final class CodingFlow {

    private static final Logger log = LoggerFactory.getLogger(CodingFlow.class);

    static final String PRD = "prd";
    static final String DEV = "dev";
    static final String ACCEPT = "accept";
    private static final int[] DONE_PROGRESS = {33, 66, 100};

    static final String PRD_RETRY_PROMPT = """
            上一步没有产出 PRD。请立刻按任务说明输出一份 PRD（Markdown，必须有 ## 背景 / ## 需求 / ## 技术思路 三个小节）\
            作为回复，不要写文件，也不要只回复确认语或别的内容。""";

    /** Record-aware (parameter-names module) mapper for the phase JSON persisted via {@link TaskPhaseStore}. */
    private static final ObjectMapper PHASE_JSON = new ObjectMapper().findAndRegisterModules();

    private final TaskPhaseStore phaseStore;
    private final String template = loadTemplate();

    CodingFlow(TaskPhaseStore phaseStore) {
        this.phaseStore = phaseStore;
    }

    static List<TaskPhase> initialPhases() {
        return List.of(
                new TaskPhase(PRD, "PRD", TaskPhaseStatus.PENDING, null, null),
                new TaskPhase(DEV, "开发", TaskPhaseStatus.PENDING, null, null),
                new TaskPhase(ACCEPT, "验收", TaskPhaseStatus.PENDING, null, null));
    }

    /** The first phase not yet done; {@code phases.size()} when all are. */
    static int currentIndex(List<TaskPhase> phases) {
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i).status() != TaskPhaseStatus.DONE) {
                return i;
            }
        }
        return phases.size();
    }

    /** Progress while phase {@code i} runs: the previous phase's done-boundary (10% for the first). */
    static int runningProgress(int i) {
        return i == 0 ? 10 : DONE_PROGRESS[i - 1];
    }

    static int doneProgress(int i) {
        return DONE_PROGRESS[i];
    }

    List<TaskPhase> load(String taskId) {
        Optional<String> json = phaseStore.findPhasesJson(taskId);
        if (json.isEmpty()) {
            return initialPhases();
        }
        try {
            return PHASE_JSON.readValue(json.get(), new TypeReference<List<TaskPhase>>() { });
        } catch (IOException e) {
            log.warn("could not parse phases for task {}: {}", taskId, e.getMessage());
            return initialPhases();
        }
    }

    void persist(String taskId, List<TaskPhase> phases) {
        try {
            phaseStore.save(taskId, PHASE_JSON.writeValueAsString(phases));
        } catch (JsonProcessingException e) {
            log.warn("could not persist phases for task {}: {}", taskId, e.getMessage());
        }
    }

    String prompt(String key, String brief) {
        String spec = brief == null ? "" : brief;
        return switch (key) {
            case PRD -> """
                    你在执行一个【编码任务】，采用平台强制的三步流程：PRD → 开发 → 验收。当前是【第1步：PRD】。
                    请充分理解下面的任务说明，产出一份 PRD（Markdown），必须包含三个小节：## 背景 / ## 需求 / ## 技术思路。
                    直接把 PRD 的 Markdown 作为你的最终回复输出，不要写入文件，也不要只回复确认语。
                    如遇必须由用户拍板的关键决策，输出一行以 %s: 开头的问题并停止。

                    %s

                    任务说明：
                    %s""".formatted(TaskService.CONFIRM_MARKER, template, spec);
            case DEV -> """
                    很好，PRD 已确认。现在是【第2步：开发】。
                    请依据上面的 PRD 实现所需改动（编辑/新增代码），完成后简述你改了哪些文件、做了什么。
                    如遇关键决策，输出一行以 %s: 开头的问题并停止。""".formatted(TaskService.CONFIRM_MARKER);
            case ACCEPT -> """
                    现在是【第3步：验收】。
                    请对改动进行验收：运行相关测试 / 构建 / 必要的 curl 验证。
                    前端任务尽量用 Playwright 截图；若环境无无头浏览器，请如实说明并降级为“构建通过 + 关键 DOM 断言”，不要编造截图。
                    必须给出验收证据（命令输出、测试结果、断言等）。如缺少关键依赖需用户决策，输出一行以 %s: 开头的问题并停止。"""
                    .formatted(TaskService.CONFIRM_MARKER);
            default -> spec;
        };
    }

    /**
     * The prompt that picks a run up again at the step it stopped on ({@code null} once every step is done). A fresh
     * session sees none of the earlier turns, so it gets the task and the PRD along with the step.
     */
    String resumePrompt(List<TaskPhase> phases, String brief, boolean freshSession) {
        int i = currentIndex(phases);
        if (i >= phases.size()) {
            return null;
        }
        String key = phases.get(i).key();
        StringBuilder sb = new StringBuilder();
        if (!freshSession) {
            sb.append("（上一次执行中断了。请从中断处继续当前这一步，已完成的部分不要重做。）\n\n");
        } else {
            sb.append(TaskService.FRESH_NOTE).append("\n\n");
            if (!PRD.equals(key)) { // the PRD step's own prompt carries the task
                sb.append("任务说明：\n").append(brief == null ? "" : brief).append("\n\n");
                String prd = phases.get(0).content();
                if (prd != null && !prd.isBlank()) {
                    sb.append("已确认的 PRD：\n").append(prd).append("\n\n");
                }
            }
        }
        return sb.append(prompt(key, brief)).toString();
    }

    /** A PRD has its three sections as headings; anything else (a summary, a question) isn't one. */
    static boolean isPrd(String text) {
        if (text == null) {
            return false;
        }
        List<String> headings = text.lines().map(String::strip).filter(l -> l.startsWith("#")).toList();
        return List.of("背景", "需求", "技术思路").stream().allMatch(h -> headings.stream().anyMatch(l -> l.contains(h)));
    }

    private static String loadTemplate() {
        try (InputStream in = CodingFlow.class.getResourceAsStream("/flows/coding.md")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
