package com.superagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.provider.ModelRouter;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStore;
import com.superagent.core.task.TaskTagStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.usage.UsageContext;
import com.superagent.platform.provider.ModelCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tags a task with its topics once it is done, on the cheapest model that can run: two to four short tags, reusing
 * the ones the owner already has where they fit, so tasks on the same subject gather under one tag. A task the owner
 * has tagged by hand keeps its tags. Subtasks aren't tagged: their complex task is.
 */
@Component
class TaskTagger {

    private static final Logger log = LoggerFactory.getLogger(TaskTagger.class);
    private static final long ASK_SECONDS = 120;
    private static final int KNOWN_TAGS = 40;

    private final TaskStore tasks;
    private final ObjectProvider<TaskTagStore> tagStore;
    private final ObjectProvider<ModelRouter> router;
    private final ObjectProvider<ModelCall> call;
    private final TaskService service;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "task-tagger");
        t.setDaemon(true);
        return t;
    });

    TaskTagger(TaskStore tasks, ObjectProvider<TaskTagStore> tagStore, ObjectProvider<ModelRouter> router,
               ObjectProvider<ModelCall> call, TaskService service) {
        this.tasks = tasks;
        this.tagStore = tagStore;
        this.router = router;
        this.call = call;
        this.service = service;
    }

    @EventListener
    public void onTaskStatus(NoticeEvents.TaskStatusChanged e) {
        TaskTagStore store = tagStore.getIfAvailable();
        if (!"done".equals(e.status()) || store == null || router.getIfAvailable() == null || call.getIfAvailable() == null) {
            return;
        }
        worker.execute(TenantContext.wrap(() -> {
            try {
                tag(e.taskId(), e.result(), store);
            } catch (RuntimeException ex) {
                log.warn("couldn't tag task {}: {}", e.taskId(), ex.getMessage());
            }
        }));
    }

    private void tag(String taskId, String result, TaskTagStore store) {
        Task task = tasks.findTask(taskId).orElse(null);
        if (task == null || !store.tags(taskId).isEmpty()) {
            return; // gone, or already tagged (by hand, or on an earlier finish)
        }
        var model = router.getObject().cheapest().orElse(null);
        if (model == null) {
            return;
        }
        String output = result != null ? result : service.lastAnswer(taskId);
        String answer = call.getObject().ask(model.model(), prompt(task, output, known(task.createdBy(), store)), ASK_SECONDS,
                new UsageContext(task.createdBy(), UsageContext.SOURCE_TASK_TAGS, null, taskId, null, model.cli()));
        List<String> tags = TaskService.cleanTags(parse(answer));
        if (!tags.isEmpty()) {
            store.set(taskId, tags.subList(0, Math.min(4, tags.size())));
            log.info("tagged task {}: {}", taskId, tags);
        }
    }

    /** The owner's tags, most used first, for the model to reuse. */
    private List<String> known(String owner, TaskTagStore store) {
        Map<String, Integer> count = new HashMap<>();
        List<String> mine = tasks.listTasks().stream().filter(t -> owner != null && owner.equals(t.createdBy()))
                .map(Task::id).toList();
        Map<String, List<String>> all = store.all();
        for (String id : mine) {
            all.getOrDefault(id, List.of()).forEach(t -> count.merge(t, 1, Integer::sum));
        }
        return count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(KNOWN_TAGS).map(Map.Entry::getKey).toList();
    }

    static String prompt(Task task, String output, List<String> known) {
        return """
                给下面这个任务打 2～4 个主题标签，用来以后按主题把相关的任务归到一起、汇总花费。
                - 标签是主题：项目、客户、产品、领域、研究对象（例如「竞品分析」「定价」「某客户名」），不要写「任务」「完成」「报告」这类空泛的词。
                - 每个标签 2～10 个字，用任务本身的语言。
                - 已有标签里合适的就直接用（字要一模一样），不合适再起新的。
                已有标签：%s

                任务标题：%s
                任务说明：
                %s

                任务结论（节选）：
                %s

                只输出一个 JSON 数组，例如 ["竞品分析", "定价"]，不要别的内容。
                """.formatted(known.isEmpty() ? "（还没有）" : String.join("、", known), task.title(),
                clip(task.brief(), 1500), clip(output, 2500));
    }

    List<String> parse(String answer) {
        if (answer == null) {
            return List.of();
        }
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode node = json.readTree(answer.substring(start, end + 1));
            List<String> out = new ArrayList<>();
            node.forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) {
            return "（无）";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
