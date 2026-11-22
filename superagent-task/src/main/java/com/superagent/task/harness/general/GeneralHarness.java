package com.superagent.task.harness.general;

import com.superagent.core.task.Task;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import com.superagent.task.harness.HarnessState.Status;
import com.superagent.task.harness.TaskHarness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The general flow: criteria (the deliverables and acceptance criteria as a checklist; vague ones spelled out by the
 * agent, and marked so) → execute → check (the platform's own checks, then a cheaper model in a new conversation that
 * sees only the checklist and the work; failures go back to execute, twice at most, then the user decides) → deliver.
 * A complex task's conclusion is checked the same way ({@link #checkSummary}).
 */
@Component
public class GeneralHarness implements TaskHarness {

    private static final Logger log = LoggerFactory.getLogger(GeneralHarness.class);

    public static final String KIND = "general", COMPLEX = "complex";
    static final String CRITERIA = "criteria", EXECUTE = "execute", CHECK = "check", DELIVER = "deliver";
    /** How many times a failed check sends the work back before the user is asked. */
    static final int RETURNS = 2;
    private static final String INTRODUCED = "introduced";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean usesTaskSession() {
        return true;
    }

    @Override
    public boolean reopens() {
        return true;
    }

    @Override
    public HarnessState start(Task task, String delivery) {
        return state(KIND, "执行");
    }

    /** A complex task's record: its conclusion is what is checked. */
    public HarnessState startSummary() {
        return state(COMPLEX, "汇总");
    }

    private static HarnessState state(String harness, String work) {
        HarnessState s = new HarnessState();
        s.harness = harness;
        s.steps = new ArrayList<>(List.of(new HarnessState.Step(CRITERIA, "明确标准"), new HarnessState.Step(EXECUTE, work),
                new HarnessState.Step(CHECK, "检查"), new HarnessState.Step(DELIVER, "交付")));
        s.roundLimit = RETURNS + 1;
        return s;
    }

    @Override
    public void drive(HarnessRun run) {
        HarnessState s = run.state();
        String reply = run.reply();
        if (s.finished() && reply != null) {
            // A message after delivery: another round on the same checklist, with the user's words.
            reopen(s, reply);
            reply = null;
            run.save();
        }
        while (!run.settled()) {
            HarnessState.Step step = s.current();
            if (step == null) {
                run.finish(GeneralPrompts.output(run.task(), s, run.host().produced(run.task())));
                return;
            }
            if (run.cancelled()) {
                run.stopped(step);
                return;
            }
            String answer = reply != null && step.key.equals(s.waitingOn) ? reply : null;
            if (reply != null && answer == null) {
                s.hint = reply; // said while not asked: it still counts for the work
            }
            reply = null;
            try {
                switch (step.key) {
                    case CRITERIA -> criteria(run, step, answer);
                    case EXECUTE -> execute(run, step, answer);
                    case CHECK -> check(run, step, answer);
                    case DELIVER -> {
                        run.begin(step);
                        run.done(step, "已交付");
                    }
                    default -> throw new IllegalStateException("Unknown step " + step.key);
                }
            } catch (com.superagent.core.error.NeedsUserException e) {
                run.waitFor(step, step.name + "这一步停下了：" + e.getMessage()); // no retry or stronger model fixes it
            } catch (RuntimeException e) {
                log.warn("task {}: general step {} failed", run.task().id(), step.key, e);
                run.fail(step, step.name + "这一步出错：" + e.getMessage());
            }
        }
    }

    // --- steps ---

    private void criteria(HarnessRun run, HarnessState.Step step, String answer) {
        HarnessState s = run.state();
        run.begin(step);
        String prompt = answer != null ? "用户的答复：\n" + answer + GeneralPrompts.CRITERIA : GeneralPrompts.CRITERIA;
        HarnessHost.Turn turn = turn(run, prompt, CRITERIA);
        if (turn.cancelled()) {
            run.stopped(step);
            return;
        }
        if (asked(run, step, turn)) {
            return;
        }
        take(s, GeneralPrompts.parseChecklist(turn.text()));
        long added = s.criteria.stream().filter(HarnessState.Criterion::agentAdded).count();
        run.done(step, s.criteria.size() + " 条" + (added > 0 ? " · " + added + " 条由 Agent 补全" : ""));
    }

    private void execute(HarnessRun run, HarnessState.Step step, String answer) {
        HarnessState s = run.state();
        run.begin(step, step.name + (s.round > 1 ? " · 第 " + s.round + " 轮" : ""));
        HarnessState.CheckRound failed = lastCheck(s);
        String prompt = answer != null ? "\n\n" + answer
                : s.hint != null ? GeneralPrompts.revise(s, s.hint, failed)
                : failed != null && !failed.passed() ? GeneralPrompts.fix(s, failed)
                : GeneralPrompts.execute(s);
        s.hint = null;
        HarnessHost.Turn turn = turn(run, prompt, EXECUTE);
        if (turn.cancelled()) {
            run.stopped(step);
            return;
        }
        if (asked(run, step, turn)) {
            return;
        }
        s.reply = turn.text();
        run.done(step, GeneralPrompts.firstLine(turn.text()));
    }

    private void check(HarnessRun run, HarnessState.Step step, String answer) {
        HarnessState s = run.state();
        if (answer != null) {
            if (GeneralPrompts.approves(answer)) {
                run.note("你确认照现在的样子交付");
                run.done(step, "你确认交付");
            } else {
                s.hint = answer;
                nextRound(s, s.round + RETURNS + 1);
                run.save();
            }
            return;
        }
        switch (checkOnce(run, step)) {
            case PASSED, STOPPED -> { }
            case RETRY -> {
                HarnessState.CheckRound last = lastCheck(s);
                run.tell("检查第 " + last.round() + " 轮没通过（" + String.join("；", last.issues().stream().limit(3).toList())
                        + "），退回重做，做完重新检查");
                nextRound(s, s.roundLimit);
                run.save();
            }
            case EXHAUSTED -> {
                List<String> issues = lastCheck(s).issues();
                if (run.host().onItsOwn(run.task())) {
                    run.note("检查 " + s.round + " 轮仍未通过，按现在的样子交付并列出问题");
                    run.done(step, "未通过，照现状交付");
                } else {
                    run.waitFor(step, GeneralPrompts.stuck(s, issues));
                }
            }
        }
    }

    enum Outcome { PASSED, RETRY, EXHAUSTED, STOPPED }

    /** One check of the work: the platform's checks; when they pass, the independent checker's verdict. */
    private Outcome checkOnce(HarnessRun run, HarnessState.Step step) {
        HarnessState s = run.state();
        Task task = run.task();
        run.begin(step, step.name + (s.round > 1 ? " · 第 " + s.round + " 轮" : ""));
        List<HarnessHost.Produced> produced = run.host().produced(task);
        List<HarnessState.PlatformCheck> platform = OutputChecks.run(s.deliverables, produced, run.host().materials(task), s.reply,
                run.host().styleLexicon());
        platform.forEach(p -> run.note((!p.passed() ? "✕ " : p.warning() ? "⚠ " : "✓ ") + p.name() + "：" + p.detail()));
        List<String> issues = new ArrayList<>(platform.stream().filter(p -> !p.passed()).map(p -> p.name() + "：" + p.detail()).toList());
        String verdict = null;
        String checker = null;
        boolean passed = issues.isEmpty();
        if (passed) {
            HarnessHost.Model model = run.host().checkerModel(task).orElse(null);
            checker = model != null ? model.model() : task.model();
            run.note("独立检查（新会话" + (checker == null ? "" : " · " + checker) + "）");
            // A new conversation every time: the checker sees the checklist and the work, never how it was done.
            HarnessHost.Turn turn = run.turnOn(model, GeneralPrompts.check(task.title(), s, produced, s.reply),
                    UUID.randomUUID().toString(), CHECK);
            if (turn.cancelled()) {
                run.stopped(step);
                return Outcome.STOPPED;
            }
            GeneralPrompts.Review r = GeneralPrompts.parseReview(turn.text(), s.criteria);
            s.criteria = r.criteria();
            verdict = r.verdict();
            passed = r.passed();
            issues.addAll(r.issues());
        }
        List<HarnessState.CheckRound> checks = new ArrayList<>(s.checks);
        checks.add(new HarnessState.CheckRound(s.round, Instant.now(), passed, platform, verdict, checker, issues));
        s.checks = checks;
        run.save();
        if (passed) {
            run.done(step, "第 " + s.round + " 轮通过");
            return Outcome.PASSED;
        }
        return s.round >= s.roundLimit ? Outcome.EXHAUSTED : Outcome.RETRY;
    }

    // --- a complex task's conclusion ---

    /**
     * Checks a complex task's conclusion like a general task's work. Returns the prompt for another summing-up turn
     * when it failed and rounds are left; null when it is to be delivered (passed, or failed with no rounds left: then
     * delivered with its problems listed) or the run was stopped ({@link HarnessRun#settled()}).
     */
    public String checkSummary(HarnessRun run, String summary) {
        HarnessState s = run.state();
        HarnessState.Step criteria = s.step(CRITERIA);
        if (criteria.status != Status.DONE) {
            run.begin(criteria);
            HarnessHost.Turn turn = run.turnOn(run.host().checkerModel(run.task()).orElse(null),
                    "任务说明：\n" + run.task().brief() + GeneralPrompts.CRITERIA.replace("这一轮先不要开始做。", ""),
                    UUID.randomUUID().toString(), CRITERIA);
            if (turn.cancelled()) {
                run.stopped(criteria);
                return null;
            }
            take(s, GeneralPrompts.parseChecklist(turn.text()));
            run.done(criteria, s.criteria.size() + " 条");
        }
        HarnessState.Step work = s.step(EXECUTE);
        run.begin(work, work.name + (s.round > 1 ? " · 第 " + s.round + " 轮" : ""));
        s.reply = summary;
        run.done(work, GeneralPrompts.firstLine(summary));
        HarnessState.Step step = s.step(CHECK);
        Outcome o = checkOnce(run, step);
        if (o == Outcome.STOPPED) {
            return null;
        }
        if (o == Outcome.RETRY) {
            String again = GeneralPrompts.fix(s, lastCheck(s));
            nextRound(s, s.roundLimit);
            run.save();
            run.tell("汇总的检查第 " + (s.round - 1) + " 轮没通过，重新汇总");
            return again + "\n\n这是在修改最后的汇总：直接给出改好的完整结论，不要再拆子任务。";
        }
        if (o == Outcome.EXHAUSTED) {
            run.note("汇总检查了 " + s.round + " 轮仍未通过，照现状交付并列出问题");
            run.done(step, "未通过，照现状交付");
        }
        HarnessState.Step deliver = s.step(DELIVER);
        run.begin(deliver);
        run.done(deliver, "已交付");
        return null;
    }

    /** What the conclusion is delivered as: unchanged when it passed, with the open problems when it didn't. */
    public static String summaryOutput(HarnessState s, String summary) {
        HarnessState.CheckRound last = lastCheck(s);
        if (last == null || last.passed()) {
            return summary;
        }
        StringBuilder sb = new StringBuilder(summary == null ? "" : summary.strip()).append("\n\n### 检查未通过的问题\n");
        last.issues().forEach(i -> sb.append("- ⚠️ ").append(i).append("\n"));
        return sb.toString();
    }

    // --- helpers ---

    /**
     * A turn in the task's own session. A conversation that hasn't had this task yet (its first, a fresh one after a
     * failure, or a reused one that held other tasks) is given the brief first.
     */
    private HarnessHost.Turn turn(HarnessRun run, String prompt, String label) {
        HarnessState s = run.state();
        boolean first = !s.conversationStarted;
        boolean introduced = s.conversation != null && s.conversation.equals(s.data.get(INTRODUCED));
        String full = introduced ? prompt.strip() : run.host().brief(run.task(), first) + prompt;
        HarnessHost.Turn t = run.turn(full, s.conversation, first, label);
        s.conversationStarted = true;
        if (s.conversation != null) {
            s.data.put(INTRODUCED, s.conversation);
        }
        run.save();
        return t;
    }

    /** The agent stopped to ask: the task waits on the user, unless it runs on its own (then its question is its answer). */
    private static boolean asked(HarnessRun run, HarnessState.Step step, HarnessHost.Turn turn) {
        if (!GeneralPrompts.asks(turn.text()) || run.host().onItsOwn(run.task())) {
            return false;
        }
        run.waitFor(step, GeneralPrompts.question(turn.text()));
        return true;
    }

    private static void take(HarnessState s, GeneralPrompts.Checklist c) {
        s.deliverables = c.deliverables();
        s.criteria = c.criteria().isEmpty()
                ? List.of(new HarnessState.Criterion("完成任务说明里的全部要求，交付物齐全、内容具体可用", false, null, true))
                : c.criteria();
    }

    private static void reopen(HarnessState s, String reply) {
        s.hint = reply;
        nextRound(s, s.round + RETURNS + 1);
    }

    /** The next round: execute and check (and delivery) run again; {@code limit} is the last round that may run. */
    private static void nextRound(HarnessState s, int limit) {
        s.round++;
        s.roundLimit = Math.max(limit, s.round);
        for (HarnessState.Step st : s.steps) {
            if (st.key.equals(EXECUTE) || st.key.equals(CHECK) || st.key.equals(DELIVER)) {
                st.status = Status.PENDING;
                st.startedAt = null;
                st.finishedAt = null;
                st.note = null;
            }
        }
        s.waitingOn = null;
    }

    private static HarnessState.CheckRound lastCheck(HarnessState s) {
        return s.checks.isEmpty() ? null : s.checks.get(s.checks.size() - 1);
    }
}
