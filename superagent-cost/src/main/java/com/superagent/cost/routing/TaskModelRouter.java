package com.superagent.cost.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskKind;
import com.superagent.core.task.TaskModelChoice;
import com.superagent.core.task.TaskModelSelector;
import com.superagent.core.usage.UsageContext;
import com.superagent.cost.ModelPriceService;
import com.superagent.cost.ModelPriceService.Candidate;
import com.superagent.platform.provider.ModelCall;
import com.superagent.platform.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks each task's model before it runs: the cheapest one able to do the job well. The admin's default model (the
 * one conversations run on) reads the task and the models tasks may use, with their prices, and picks; what it knows
 * of those models fills the gap we have — prices are known here, ability isn't. If it can't answer, the dearest other
 * models try; if none can, the task runs on the default model and the record says why.
 *
 * <p>When a task fails it moves to the next dearer model and starts over, step by step up to the default model: the
 * outcome stays in hand, and the cost only rises as far as it must.
 */
@Service
public class TaskModelRouter implements TaskModelSelector {

    private static final Logger log = LoggerFactory.getLogger(TaskModelRouter.class);
    /** How many models try to judge before giving up on judging. */
    private static final int JUDGES = 3;
    private static final long JUDGE_SECONDS = 180;
    private static final int BRIEF_CHARS = 4000;
    private static final List<String> DIFFICULTIES = List.of("easy", "medium", "hard");

    private final ModelPriceService prices;
    private final SettingsService settings;
    private final ModelCall call;
    private final ObjectMapper json = new ObjectMapper();

    public TaskModelRouter(ModelPriceService prices, SettingsService settings, ModelCall call) {
        this.prices = prices;
        this.settings = settings;
        this.call = call;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Optional<TaskModelChoice> choose(Task task) {
        List<Candidate> all = prices.candidates();
        List<Candidate> forTasks = all.stream().filter(Candidate::forTasks).toList();
        if (forTasks.isEmpty()) {
            return Optional.empty();
        }
        String listed = describe(forTasks);
        Candidate fallback = defaultFor(forTasks);
        List<String> problems = new ArrayList<>();
        for (Candidate judge : judges(all)) {
            try {
                // Choosing a task's model is part of the task: on its creator, under the task.
                String answer = call.ask(judge.id(), prompt(task, listed), JUDGE_SECONDS, new UsageContext(task.createdBy(),
                        UsageContext.SOURCE_MODEL_CHOICE, null, task.id(), null, null));
                return Optional.of(verdict(task, forTasks, fallback, listed, judge.id(), answer));
            } catch (RuntimeException e) {
                log.warn("{} couldn't judge task {}: {}", judge.id(), task.id(), e.getMessage());
                problems.add(judge.id() + "：" + clip(e.getMessage(), 200));
            }
        }
        return Optional.of(new TaskModelChoice(task.id(), 0, TaskModelChoice.BY_FALLBACK, null, null, fallback.id(),
                fallback.cli(), null, listed, "没能判断这个任务（" + (problems.isEmpty() ? "没有可用来判断的模型" : String.join("；", problems))
                + "），用" + (fallback.id().equals(defaultModel()) ? "默认模型 " : "最贵的 ") + fallback.id() + "。", Instant.now()));
    }

    @Override
    public Optional<TaskModelChoice> escalate(Task task, String failure) {
        List<Candidate> forTasks = prices.candidates().stream().filter(Candidate::forTasks).toList();
        Candidate top = defaultFor(forTasks);
        if (top == null || top.id().equals(task.model())) {
            return Optional.empty(); // already on the default model: nothing further up to try
        }
        Candidate current = forTasks.stream().filter(c -> c.id().equals(task.model())).findFirst().orElse(null);
        // The next dearer model, not past the default one; without prices to compare, straight to the default.
        Candidate next = current == null || !current.priced() || !top.priced() ? top
                : forTasks.stream().filter(Candidate::priced)
                        .filter(c -> c.blended() > current.blended() && c.blended() <= top.blended())
                        .min(Comparator.comparingDouble(Candidate::blended)).orElse(top);
        if (next.id().equals(task.model())) {
            return Optional.empty();
        }
        return Optional.of(new TaskModelChoice(task.id(), 0, TaskModelChoice.BY_ESCALATION, null, null, next.id(),
                next.cli(), null, describe(forTasks), "在 " + task.model() + " 上失败：" + clip(failure, 300)
                + "。换下一个更贵的 " + next.id() + (next.id().equals(top.id()) ? "（默认模型，再失败就不再换）" : "")
                + " 重新执行。", Instant.now()));
    }

    // --- judging ---

    private String defaultModel() {
        return settings.agentDefaultModel();
    }

    /** The default model when tasks may use it; else the dearest that tasks may use. */
    private Candidate defaultFor(List<Candidate> forTasks) {
        String model = defaultModel();
        return forTasks.stream().filter(c -> c.id().equals(model)).findFirst()
                .orElseGet(() -> forTasks.stream().filter(Candidate::priced).max(Comparator.comparingDouble(Candidate::blended))
                        .orElse(forTasks.isEmpty() ? null : forTasks.get(forTasks.size() - 1)));
    }

    /** What conversations run on (the default model, or its stand-in), then the dearest others. */
    private List<Candidate> judges(List<Candidate> all) {
        List<Candidate> out = new ArrayList<>();
        for (String m : prices.chatModels()) {
            all.stream().filter(c -> c.id().equals(m)).findFirst().filter(c -> !out.contains(c)).ifPresent(out::add);
            if (out.size() >= JUDGES) {
                return out;
            }
        }
        all.stream().filter(Candidate::priced).sorted(Comparator.comparingDouble(Candidate::blended).reversed())
                .filter(c -> !out.contains(c)).limit(Math.max(0, JUDGES - out.size())).forEach(out::add);
        return out;
    }

    static String prompt(Task task, String listed) {
        String kind = TaskKind.fromStorage(task.flow()) == TaskKind.CODING ? "编码任务（要改代码、跑命令、验证）" : "通用任务";
        String brief = task.brief() == null ? "" : task.brief();
        return """
                你在为一个即将执行的任务选模型。目标：在保证能做好的前提下，尽量便宜——成本是核心诉求。

                ## 任务
                标题：%s
                类型：%s
                说明：
                %s

                ## 现在能用的模型（价格是每百万 token 的美元价，输入 / 输出，从便宜到贵）
                %s

                ## 怎么选
                1. 先判断难度：easy（简单整理、改写、格式转换、照着做的小活）、medium（要理解和组织的常规工作：写文档、一般分析、小范围改代码）、hard（复杂推理、大范围改代码、调研要求高、出错代价大）。
                2. 再从上面的列表里选一个**能做好这件事的最便宜的模型**。按你对这些模型能力的了解来判断，同样能做好时选便宜的。
                3. 只能选列表里的模型，写它的完整 id。

                只输出一个 JSON，不要别的内容：
                {"difficulty": "easy|medium|hard", "model": "<模型 id>", "reason": "<一两句话：难在哪里、为什么这个模型够用又便宜>"}
                """.formatted(task.title(), kind, clip(brief, BRIEF_CHARS), listed);
    }

    /** The judge's answer as a choice; a model it named that tasks can't use is replaced by the rule for its difficulty. */
    TaskModelChoice verdict(Task task, List<Candidate> forTasks, Candidate fallback, String listed, String judge, String answer) {
        JsonNode node = parse(answer);
        String difficulty = node.path("difficulty").asText("").strip().toLowerCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(difficulty)) {
            throw new IllegalStateException("答案里没有可用的难度：" + clip(answer, 200));
        }
        String reason = node.path("reason").asText("").strip();
        String named = node.path("model").asText("").strip();
        Candidate pick = forTasks.stream().filter(c -> c.id().equals(named)).findFirst().orElse(null);
        if (pick == null) {
            pick = byDifficulty(forTasks, fallback, difficulty);
            reason = (reason.isEmpty() ? "" : reason + " ") + "（它选的「" + named + "」任务用不了，按难度 " + difficulty
                    + " 改用 " + pick.id() + "。）";
        }
        return new TaskModelChoice(task.id(), 0, TaskModelChoice.BY_JUDGE, difficulty, null, pick.id(), pick.cli(), judge,
                listed, reason, Instant.now());
    }

    /** Without a usable pick: easy the cheapest, medium the middle by price, hard the default model. */
    private static Candidate byDifficulty(List<Candidate> forTasks, Candidate fallback, String difficulty) {
        List<Candidate> priced = forTasks.stream().filter(Candidate::priced).toList();
        if (priced.isEmpty() || "hard".equals(difficulty)) {
            return fallback;
        }
        return "easy".equals(difficulty) ? priced.get(0) : priced.get(priced.size() / 2);
    }

    private JsonNode parse(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("答案不是 JSON：" + clip(answer, 200));
        }
        try {
            return json.readTree(answer.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalStateException("答案不是 JSON：" + clip(answer, 200));
        }
    }

    static String describe(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates) {
            sb.append("- ").append(c.id()).append(" | ")
                    .append(c.priced() ? "$" + price(c.input()) + " / $" + price(c.output()) : "价格未知")
                    .append(c.released() == null ? "" : " | 发布 " + c.released())
                    .append(" | ").append(c.cli()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String price(Double p) {
        return p == null ? "?" : (p == Math.floor(p) ? String.valueOf(p.longValue()) : String.valueOf(p));
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
