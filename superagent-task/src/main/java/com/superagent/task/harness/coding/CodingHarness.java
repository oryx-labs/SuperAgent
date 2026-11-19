package com.superagent.task.harness.coding;

import com.superagent.core.task.Task;
import com.superagent.core.task.TaskModelChoice;
import com.superagent.runtime.cli.CliPaths;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import com.superagent.task.harness.HarnessState.Status;
import com.superagent.task.harness.TaskHarness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The coding flow: intake → workspace → spec (the user approves it) → develop → verify (the platform runs the checks;
 * failures go back to the agent, a few rounds at most) → review → acceptance against the spec → delivery (squash-merged
 * into the local branch, or pushed with a pull request). When the local branch moved meanwhile and the rebase onto it
 * conflicts, a 「解决冲突」 step goes in before delivery: the developer resolves it in the task's own worktree and the
 * platform judges the result (no markers left, the rebase goes through, the checks, a review of just the resolution).
 *
 * <p>Review and acceptance run on another model than the developer's, each in a conversation of its own. Bug-level
 * issues go back to the developer, in its own conversation, and the fix is verified, reviewed and accepted again, until
 * it passes or the loop stops for the user (see {@link ReviewLoop#stop}). Only what the platform can read decides:
 * exit codes, VERDICT lines, the criteria checked one by one.
 */
@Component
public class CodingHarness implements TaskHarness {

    private static final Logger log = LoggerFactory.getLogger(CodingHarness.class);

    static final String INTAKE = "intake", WORKSPACE = "workspace", SPEC = "spec", DEVELOP = "develop",
            VERIFY = "verify", REVIEW = "review", ACCEPT = "accept", RESOLVE = "resolve", DELIVER = "deliver";
    static final String RESOLVE_REVIEW = "resolve-review";
    static final int FIX_ROUNDS = 3, RESOLVE_ROUNDS = 2;
    /** Times delivery may go back to resolving (the base branch kept moving under it) before the user is asked. */
    static final int RESOLVE_TIMES = 3;

    private final int checkTimeoutSeconds;
    private CliPaths cliPaths; // null in tests: the process PATH as is
    private final PullRequests pullRequests = new PullRequests();

    public CodingHarness(@Value("${superagent.task.coding.check-timeout-seconds:1800}") int checkTimeoutSeconds) {
        this.checkTimeoutSeconds = checkTimeoutSeconds;
    }

    @Autowired(required = false)
    void setCliPaths(CliPaths cliPaths) {
        this.cliPaths = cliPaths;
    }

    @Override
    public String kind() {
        return "coding";
    }

    @Override
    public HarnessState start(Task task, String delivery) {
        HarnessState s = new HarnessState();
        s.harness = kind();
        s.steps = new ArrayList<>(List.of(new HarnessState.Step(INTAKE, "需求"), new HarnessState.Step(WORKSPACE, "环境"),
                new HarnessState.Step(SPEC, "规格"), new HarnessState.Step(DEVELOP, "开发"),
                new HarnessState.Step(VERIFY, "验证"), new HarnessState.Step(REVIEW, "审查"),
                new HarnessState.Step(ACCEPT, "验收"), new HarnessState.Step(DELIVER, "交付")));
        s.repo = task.repo() == null ? null : task.repo().strip();
        s.mode = Workspace.isRemote(s.repo) ? "remote" : "local";
        s.delivery = delivery(delivery, s.mode);
        return s;
    }

    private static String delivery(String asked, String mode) {
        return asked != null && asked.equalsIgnoreCase("pr") ? "pr"
                : asked != null && asked.equalsIgnoreCase("merge") ? "merge"
                : mode.equals("remote") ? "pr" : "merge";
    }

    @Override
    public void afterRestart(Task task, HarnessState state, HarnessHost host) {
        HarnessState.Step step = state.current();
        if (step == null || !step.key.equals(DELIVER)) {
            return; // only a delivery touches the user's repository
        }
        new MergeRecovery(new Git(env())).repair(state).forEach(problem -> host.tell(task, problem));
    }

    @Override
    public void drive(HarnessRun run) {
        HarnessState s = run.state();
        String reply = run.reply();
        Workspace ws = new Workspace(new Git(env()));
        while (!run.settled()) {
            HarnessState.Step step = s.current();
            if (step == null) {
                run.finish(output(run.task(), s));
                return;
            }
            if (run.cancelled()) {
                run.stopped(step);
                return;
            }
            String answer = reply != null && step.key.equals(s.waitingOn) ? reply : null;
            reply = null; // a reply answers the step that asked, once
            try {
                switch (step.key) {
                    case INTAKE -> intake(run, step, ws, answer);
                    case WORKSPACE -> workspace(run, step, ws);
                    case SPEC -> spec(run, step, answer);
                    case DEVELOP -> develop(run, step, ws, answer);
                    case VERIFY -> verify(run, step, ws, answer);
                    case REVIEW -> review(run, step, ws, answer);
                    case ACCEPT -> accept(run, step, ws, answer);
                    case RESOLVE -> resolve(run, step, ws, answer);
                    case DELIVER -> deliver(run, step, ws, answer);
                    default -> throw new IllegalStateException("Unknown step " + step.key);
                }
            } catch (IllegalArgumentException e) {
                run.waitFor(step, e.getMessage()); // something the user can fix (a path, a branch, a token)
            } catch (RuntimeException | IOException e) {
                log.warn("task {}: coding step {} failed", run.task().id(), step.key, e);
                run.fail(step, step.name + "这一步出错：" + e.getMessage());
            }
        }
    }

    @Override
    public void precheck(String repo, String branch, String delivery, String owner, HarnessHost host) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("编码任务要给代码位置：这台服务器上的本地仓库路径，或 git 仓库地址（https://…）");
        }
        HarnessState s = new HarnessState();
        s.repo = repo.strip();
        s.mode = Workspace.isRemote(s.repo) ? "remote" : "local";
        s.delivery = delivery(delivery, s.mode);
        if (s.mode.equals("local") && s.delivery.equals("pr")) {
            throw new IllegalArgumentException("本地仓库只能「合并回本地分支」交付；要推送并开 PR，请给 git 仓库地址");
        }
        Workspace ws = new Workspace(new Git(env()));
        if (s.mode.equals("local")) {
            ws.resolve(s, blank(branch), host.localRoots());
            return;
        }
        Map<String, String> auth = remoteAuth(host, owner, s);
        ws.reachable(s.repo, blank(branch), auth);
    }

    @Override
    public void discard(HarnessState state) {
        new Workspace(new Git(env())).discard(state);
    }

    // --- steps ---

    private void intake(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) {
        HarnessState s = run.state();
        String given = answer == null ? null : repoIn(answer);
        if (given != null && !given.equals(s.repo)) { // the user said where the code is
            s.repo = given;
            s.mode = Workspace.isRemote(given) ? "remote" : "local";
            s.delivery = delivery(null, s.mode);
        } else if (answer != null && s.mode.equals("local") && s.delivery.equals("pr")) {
            s.delivery = "merge";
        }
        run.begin(step);
        if (s.repo == null || s.repo.isBlank()) {
            throw new IllegalArgumentException("编码任务需要代码位置：一个本地仓库路径，或 git 仓库地址。回复里告诉我在哪");
        }
        if (s.mode.equals("local") && s.delivery.equals("pr")) {
            throw new IllegalArgumentException("本地仓库只能「合并回本地分支」交付；要推送并开 PR，请给 git 仓库地址。"
                    + "\n\n👉 下一步：回复「继续」按合并回本地分支做");
        }
        ws.resolve(s, blank(run.task().branch()), run.host().localRoots());
        List<String> dirty = ws.localChanges(s);
        String note = s.mode.equals("local")
                ? "本地仓库 " + s.repoRoot + " · 分支 " + s.baseBranch + " @ " + CodingPrompts.shortSha(s.baseSha)
                : "远程仓库 " + s.repo;
        if (!dirty.isEmpty()) {
            run.note("提醒：你的项目里有 " + dirty.size() + " 个未提交的改动（" + String.join("、", dirty.stream().limit(5).toList())
                    + (dirty.size() > 5 ? "…" : "") + "）。任务在单独的工作区里做，看不到这些改动；最后合并时如果碰到同一个文件，会停下来告诉你。");
        }
        run.done(step, note);
    }

    private void workspace(HarnessRun run, HarnessState.Step step, Workspace ws) throws IOException {
        HarnessState s = run.state();
        run.begin(step);
        Map<String, String> auth = s.mode.equals("remote") ? remoteAuth(run.host(), run.task().createdBy(), s) : Map.of();
        Path dir = ws.provision(s, run.host().workRoot(), run.task().id(), auth);
        run.workIn(dir.toString());
        if (s.conversation == null) {
            s.conversation = UUID.randomUUID().toString();
            s.conversationStarted = false;
        }
        run.done(step, "分支 " + s.branch + " · 工作区 " + dir);
    }

    private void spec(HarnessRun run, HarnessState.Step step, String answer) {
        HarnessState s = run.state();
        if (answer != null && s.spec != null && CodingPrompts.approves(answer)) {
            s.specApproved = true;
            run.note("规格已确认，开始开发");
            run.done(step, "已确认");
            return;
        }
        run.begin(step);
        HarnessHost.Turn turn = turn(run, CodingPrompts.spec(run.task().title(), run.task().brief(), s, answer), SPEC);
        if (turn.cancelled()) {
            run.stopped(step);
            return;
        }
        CodingPrompts.Spec spec = CodingPrompts.parseSpec(turn.text());
        if (!spec.usable()) {
            turn = turn(run, CodingPrompts.SPEC_RETRY, SPEC);
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            spec = CodingPrompts.parseSpec(turn.text());
        }
        if (!spec.usable()) {
            throw new IllegalArgumentException("规格里没有可检查的验收标准或验证命令。回复补充一下要达到什么效果、用什么命令验证");
        }
        s.spec = spec.text();
        s.criteria = spec.criteria().stream().map(c -> new HarnessState.Criterion(c, false, null)).toList();
        s.verifyCommands = spec.commands();
        s.specApproved = false;
        run.waitFor(step, specSummary(s));
    }

    private void develop(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) {
        HarnessState s = run.state();
        run.begin(step);
        String extra = answer == null || CodingPrompts.approves(answer) ? "" : "\n用户的补充（照这个做）：\n" + answer + "\n";
        HarnessHost.Turn turn = turn(run, CodingPrompts.develop(s) + extra, DEVELOP);
        if (turn.cancelled()) {
            run.stopped(step);
            return;
        }
        ws.commitAll(s, "开发：" + run.task().title());
        ws.recordChanges(s);
        if (s.files.isEmpty()) {
            throw new IllegalArgumentException("开发这一步没有改任何文件。回复说明还缺什么，或补充要改的地方");
        }
        resetFrom(s, VERIFY);
        run.done(step, s.files.size() + " 个文件 · " + firstLine(turn.text()));
    }

    private void verify(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) {
        HarnessState s = run.state();
        String hint = answer != null && !CodingPrompts.approves(answer) ? answer : null;
        if (answer != null) {
            s.fixBudget = FIX_ROUNDS; // the user asked for more rounds
        }
        run.begin(step);
        while (true) {
            HarnessState.Round round = check(run, s, ws);
            if (round.passed()) {
                run.done(step, "第 " + round.round() + " 轮全部通过");
                return;
            }
            if (s.fixBudget <= 0) {
                run.waitFor(step, "验证连续 " + FIX_ROUNDS + " 轮没有通过（最后失败的是 " + failing(round)
                        + "）。可以补充提示，或者停止任务。\n\n👉 下一步：回复「继续」再修 " + FIX_ROUNDS + " 轮");
                return;
            }
            s.fixBudget--;
            run.tell("验证第 " + round.round() + " 轮没通过（" + failing(round) + "），正在自动修复，修完重新验证");
            HarnessHost.Turn turn = turn(run, CodingPrompts.fix(s, round)
                    + (hint == null ? "" : "\n\n用户的提示：" + hint), VERIFY);
            hint = null;
            if (turn.cancelled()) {
                run.stopped(step);
                return;
            }
            ws.commitAll(s, "修复验证失败（第 " + round.round() + " 轮）");
            ws.recordChanges(s);
            replaceLast(s, new HarnessState.Round(round.round(), round.at(), false, round.checks(), firstLine(turn.text())));
            run.save();
        }
    }

    private void review(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) {
        HarnessState s = run.state();
        if (answer != null) {
            afterStop(run, step, ws, answer);
            return;
        }
        run.begin(step);
        s.loopRound++;
        List<HarnessState.Finding> previous = lastSentBack(s);
        Looked look = look(run, ws, CodingPrompts.review(run.task().title(), s, ws.diff(s, 80_000), s.loopRound, previous), REVIEW);
        if (look.turn().cancelled()) {
            run.stopped(step);
            return;
        }
        CodingPrompts.Review r = CodingPrompts.parseReview(look.turn().text());
        List<HarnessState.Rebuttal> ruled = rule(s, r.rulings());
        List<HarnessState.Finding> findings = new ArrayList<>(ReviewLoop.identify(s, r.findings(), previous));
        // Issues the reviewer agreed to drop don't fail the round, even if its verdict still counted them.
        boolean waivedOnly = findings.stream().anyMatch(f -> "waived".equals(f.state()));
        boolean passed = findings.stream().noneMatch(HarnessState.Finding::blocking) && ("PASS".equals(r.verdict()) || waivedOnly);
        if (!passed && findings.stream().noneMatch(HarnessState.Finding::blocking)) {
            findings.add(new HarnessState.Finding("medium", "审查没有给出 `VERDICT: PASS`（没写 VERDICT 行按不通过算），"
                    + "也没有列出具体问题：请自查规格里的要求是否都做到了", null, "open", "V"));
        }
        s.findings = findings;
        HarnessState.ReviewRound round = record(s, REVIEW, look, r.verdict(), passed, findings, List.of(), ruled);
        if (passed) {
            long tips = findings.stream().filter(f -> f.severity().equals("low")).count();
            run.done(step, "第 " + s.loopRound + " 轮通过 · " + round.model() + (tips == 0 ? "" : "（" + tips + " 条建议）"));
            return;
        }
        sendBack(run, step, ws, round, "审查");
    }

    private void accept(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) {
        HarnessState s = run.state();
        if (answer != null) {
            afterStop(run, step, ws, answer);
            return;
        }
        run.begin(step);
        Looked look = look(run, ws, CodingPrompts.accept(run.task().title(), s, ws.diff(s, 60_000), s.loopRound), ACCEPT);
        if (look.turn().cancelled()) {
            run.stopped(step);
            return;
        }
        CodingPrompts.Acceptance a = CodingPrompts.parseAccept(look.turn().text(), s.criteria);
        s.criteria = a.criteria();
        List<HarnessState.Finding> findings = new ArrayList<>();
        for (int i = 0; i < a.criteria().size(); i++) {
            HarnessState.Criterion c = a.criteria().get(i);
            if (!c.met()) {
                String id = "AC" + (i + 1);
                findings.add(new HarnessState.Finding("high", "验收标准 " + (i + 1) + " 没做到：" + c.text(), c.evidence(),
                        s.waived.contains(id) ? "waived" : "open", id));
            }
        }
        boolean waivedOnly = !findings.isEmpty() && findings.stream().noneMatch(HarnessState.Finding::blocking);
        boolean passed = findings.stream().noneMatch(HarnessState.Finding::blocking) && ("PASS".equals(a.verdict()) || waivedOnly);
        if (!passed && findings.stream().noneMatch(HarnessState.Finding::blocking)) {
            findings.add(new HarnessState.Finding("medium", "验收没有给出 `VERDICT: PASS`（没写 VERDICT 行按不通过算）："
                    + "请对照验收标准自查", null, "open", "ACV"));
        }
        HarnessState.ReviewRound round = record(s, ACCEPT, look, a.verdict(), passed, findings, a.criteria(), List.of());
        if (passed) {
            run.done(step, "第 " + s.loopRound + " 轮验收通过：" + a.criteria().stream().filter(HarnessState.Criterion::met).count()
                    + "/" + a.criteria().size() + " 条满足 · " + round.model());
            return;
        }
        sendBack(run, step, ws, round, "验收");
    }

    /** A review or acceptance that didn't pass: stop for the user if a stop rule says so, else back to the developer. */
    private void sendBack(HarnessRun run, HarnessState.Step step, Workspace ws, HarnessState.ReviewRound round, String what) {
        HarnessState s = run.state();
        List<HarnessState.Finding> blocking = round.findings().stream().filter(HarnessState.Finding::blocking).toList();
        BigDecimal spent = run.host().spend(run.task()).subtract(BigDecimal.valueOf(s.spendBase));
        String stop = ReviewLoop.stop(s.reviews.subList(Math.min(s.loopBase, s.reviews.size()), s.reviews.size()),
                run.host().reviewLimits(), spent);
        if (stop != null) {
            s.stopReason = stop;
            run.waitFor(step, stopMessage(s, stop, blocking));
            return;
        }
        run.tell("第 " + round.round() + " 轮" + what + "发现 " + blocking.size() + " 个要改的问题，交回开发模型修复，修完重新验证、审查和验收");
        fix(run, ws, blocking, what, null);
    }

    /** The developer fixes {@code issues} in its own conversation; then everything from verify on runs again. */
    private void fix(HarnessRun run, Workspace ws, List<HarnessState.Finding> issues, String from, String feedback) {
        HarnessState s = run.state();
        HarnessHost.Turn fix = turn(run, CodingPrompts.fixReview(issues, from, feedback), DEVELOP);
        if (fix.cancelled()) {
            run.stopped(s.current());
            return;
        }
        ws.commitAll(s, "按" + from + "意见修改（第 " + s.loopRound + " 轮）");
        ws.recordChanges(s);
        s.pendingRebuttals = CodingPrompts.parseRebuttals(fix.text());
        s.fixBudget = FIX_ROUNDS;
        resetFrom(s, VERIFY); // the fix is verified, reviewed and accepted again
        run.save();
    }

    /** The user answered a stop: deliver as it is, or send their words (or just another go) back to the developer. */
    private void afterStop(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) {
        HarnessState s = run.state();
        if (CodingPrompts.approves(answer) && !answer.contains("继续")) {
            run.note("你确认可以交付，剩下的问题不再修");
            s.stopReason = null;
            for (String key : List.of(REVIEW, ACCEPT)) {
                s.steps.stream().filter(st -> st.key.equals(key) && st.status != Status.DONE)
                        .forEach(st -> run.done(st, "你确认交付"));
            }
            return;
        }
        run.begin(step);
        s.stopReason = null;
        s.loopBase = s.reviews.size(); // the stop rules count afresh
        s.spendBase = run.host().spend(run.task()).doubleValue();
        List<HarnessState.Finding> left = lastSentBack(s);
        if (left.isEmpty()) {
            left = s.findings.stream().filter(HarnessState.Finding::blocking).toList(); // a task from before the loop
        }
        fix(run, ws, left, "审查和验收", CodingPrompts.approves(answer) ? null : answer);
    }

    /** What went back to the developer last: the bug-level issues of the last round that didn't pass. */
    private static List<HarnessState.Finding> lastSentBack(HarnessState s) {
        for (int i = s.reviews.size() - 1; i >= 0; i--) {
            HarnessState.ReviewRound r = s.reviews.get(i);
            if (!r.passed()) {
                return r.findings().stream().filter(HarnessState.Finding::blocking).toList();
            }
            if (r.kind().equals(REVIEW)) {
                break; // a passed review: whatever was before it is settled
            }
        }
        return List.of();
    }

    /** The reviewer's rulings on the developer's objections; an accepted one drops that issue from now on. */
    private static List<HarnessState.Rebuttal> rule(HarnessState s, List<CodingPrompts.Ruling> rulings) {
        List<HarnessState.Rebuttal> out = new ArrayList<>();
        for (HarnessState.Rebuttal r : s.pendingRebuttals) {
            CodingPrompts.Ruling ruling = rulings.stream().filter(x -> x.id().equals(r.id())).findFirst().orElse(null);
            if (ruling != null && ruling.accepted() && !s.waived.contains(r.id())) {
                s.waived = new ArrayList<>(s.waived);
                s.waived.add(r.id());
            }
            out.add(new HarnessState.Rebuttal(r.id(), r.reason(), ruling == null ? null : ruling.accepted() ? "accepted" : "rejected",
                    ruling == null ? null : ruling.reason()));
        }
        s.pendingRebuttals = new ArrayList<>();
        return out;
    }

    private HarnessState.ReviewRound record(HarnessState s, String kind, Looked look, String verdict, boolean passed,
                                            List<HarnessState.Finding> findings, List<HarnessState.Criterion> criteria,
                                            List<HarnessState.Rebuttal> rebuttals) {
        HarnessState.ReviewRound round = new HarnessState.ReviewRound(s.loopRound, kind, Instant.now(), look.model(), verdict,
                passed, (int) findings.stream().filter(HarnessState.Finding::blocking).count(), List.copyOf(findings),
                List.copyOf(criteria), rebuttals, look.reverted());
        s.reviews = new ArrayList<>(s.reviews);
        s.reviews.add(round);
        return round;
    }

    /** {@code reverted}: the reviewer changed files, and they were put back. */
    private record Looked(HarnessHost.Turn turn, boolean reverted, String model) {
    }

    /**
     * One look at the work by the reviewing model, in a conversation of its own, read-only: whatever it changes in the
     * worktree is undone. If that model can't run, the developer's model looks instead (still in a new conversation).
     */
    private Looked look(HarnessRun run, Workspace ws, String prompt, String label) {
        HarnessState s = run.state();
        HarnessHost.Model model = reviewer(run);
        Workspace.Snapshot before = ws.snapshot(s);
        HarnessHost.Turn turn;
        try {
            turn = run.reviewTurn(model, prompt, UUID.randomUUID().toString(), label);
        } catch (RuntimeException e) {
            if (model == null || run.cancelled()) {
                throw e;
            }
            log.warn("task {}: reviewer {} failed, the developer's model looks instead", run.task().id(), model.model(), e);
            run.note("审查模型 " + model.model() + " 出错（" + firstLine(e.getMessage()) + "），这一轮改用开发模型、开新会话");
            ws.restore(s, before);
            model = null;
            turn = run.reviewTurn(null, prompt, UUID.randomUUID().toString(), label);
        }
        boolean reverted = ws.restore(s, before);
        if (reverted) {
            run.note("审查模型改动了工作区里的文件，已撤销（审查和验收只读）");
        }
        run.save();
        return new Looked(turn, reverted, model == null ? run.task().model() : model.model());
    }

    /** Who looks at the work this round; a new pick goes into the task's 「模型选择」. Null: the developer's own model. */
    private static HarnessHost.Model reviewer(HarnessRun run) {
        HarnessState s = run.state();
        TaskModelChoice choice = run.host().reviewer(run.task()).filter(c -> c.model() != null).orElse(null);
        if (choice == null) {
            return null;
        }
        if (!choice.model().equals(s.reviewerModel)) {
            run.host().recordChoice(choice);
            s.reviewerModel = choice.model();
            s.reviewerCli = choice.cli();
            run.note("审查和验收用 " + choice.model() + "：" + choice.reasoning());
        }
        return new HarnessHost.Model(choice.model(), choice.cli());
    }

    /** Why the loop stopped, what is left, and how the count went round by round. */
    static String stopMessage(HarnessState s, String reason, List<HarnessState.Finding> left) {
        StringBuilder sb = new StringBuilder("审查和验收停下来了：").append(reason).append("。\n\n**还没解决的问题**\n");
        left.forEach(f -> sb.append("- [").append(CodingPrompts.label(f.severity())).append("] ").append(ReviewLoop.tag(f.id()))
                .append(" ").append(f.text()).append(f.where() == null ? "" : "（" + f.where() + "）").append("\n"));
        sb.append("\n**每轮的变化**\n");
        for (HarnessState.ReviewRound r : s.reviews) {
            sb.append("- 第 ").append(r.round()).append(" 轮").append(r.kind().equals(ACCEPT) ? "验收" : "审查")
                    .append("（").append(r.model() == null ? "?" : r.model()).append("）：")
                    .append(r.passed() ? "通过" : r.blocking() + " 个要改的问题").append("\n");
        }
        return sb.append("\n可以回复修改意见让它接着改（回复「继续」按现在的问题再修一轮）。\n\n👉 下一步：回复「确认交付」照现在的样子交付").toString();
    }

    /**
     * Merges or pushes. A reply to a stop here is acted on: "继续" tries again (the user cleared what was in the way);
     * anything else is their word on the conflicts, for the resolver.
     */
    private void deliver(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) throws IOException {
        HarnessState s = run.state();
        if (answer != null && !CodingPrompts.bareApproval(answer)) {
            s.conflictHint = answer;
        }
        if (answer != null) {
            s.resolveTimes = 0; // the user said go on: the count starts over
        }
        run.begin(step);
        String message = commitMessage(run.task(), s);
        if (s.delivery.equals("merge") && s.mode.equals("local")) {
            Workspace.Merge m = ws.mergeLocal(s, message, () -> check(run, s, ws).passed(), sha -> {
                s.deliveryResult = sha;
                run.save();
            });
            if (!m.conflicts().isEmpty()) {
                if (s.resolveTimes >= RESOLVE_TIMES) {
                    run.waitFor(step, s.baseBranch + " 一直有新提交：冲突已经解决了 " + s.resolveTimes + " 次，每次合并前又和新的提交冲突（"
                            + String.join("、", m.conflicts()) + "）。等 " + s.baseBranch + " 稳定一些再合并。"
                            + "\n\n👉 下一步：回复「继续」再解决一次");
                    return;
                }
                s.resolveTimes++;
                toResolve(run, step, m.conflicts());
                return;
            }
            if (m.problem() != null) {
                run.waitFor(step, m.problem());
                return;
            }
            long resolved = s.conflicts.stream().filter(HarnessState.ConflictRound::passed).count();
            s.deliveryResult = m.commit();
            s.deliveryNote = "已合并进 " + s.baseBranch + "（提交 " + CodingPrompts.shortSha(m.commit()) + "）"
                    + (m.rebased() ? "，合并前变基到了最新提交并重新验证" : "")
                    + (resolved > 0 && s.conflictHint != null ? "，合并前按你的指示解决了冲突"
                    : resolved > 0 ? "，合并前自动解决了冲突并重新验证、审查" : "");
            s.conflictHint = null;
            s.resolveTimes = 0;
            run.workIn(s.repoRoot);
        } else {
            Map<String, String> auth = remoteAuth(run.host(), run.task().createdBy(), s);
            ws.commitAll(s, message);
            String problem = ws.push(s, auth);
            if (problem != null) {
                throw new IllegalArgumentException(problem + "。确认你有这个仓库的推送权限后回复「继续」");
            }
            Auth a = remoteToken(run.host(), run.task().createdBy(), s);
            if (a.token() == null) {
                s.deliveryNote = "分支 " + s.branch + " 已推送；这个平台还不能自动开 PR，请在网页上从这个分支创建";
            } else {
                String url = pullRequests.open(a.connector(), s.repo, a.token(), s.branch, s.baseBranch, run.task().title(),
                        prBody(run.task(), s));
                s.deliveryResult = url;
                s.deliveryNote = "已推送分支 " + s.branch + " 并开了 " + (a.connector().equals(PullRequests.GITHUB) ? "PR" : "MR");
            }
        }
        run.done(step, s.deliveryNote);
        run.note("✅ " + s.deliveryNote);
        run.finish(output(run.task(), s));
    }

    // --- resolving conflicts ---

    /** The rebase before merging conflicted: a 「解决冲突」 step goes in before delivery (again, if there was one). */
    private void toResolve(HarnessRun run, HarnessState.Step deliver, List<String> files) {
        HarnessState s = run.state();
        HarnessState.Step r = s.steps.stream().filter(st -> st.key.equals(RESOLVE)).findFirst().orElse(null);
        if (r == null) {
            r = new HarnessState.Step(RESOLVE, "解决冲突");
            s.steps.add(s.indexOf(deliver), r);
        }
        r.status = Status.PENDING;
        r.startedAt = null;
        r.finishedAt = null;
        r.note = null;
        deliver.status = Status.PENDING;
        deliver.startedAt = null;
        deliver.note = null;
        run.tell("合并到 " + s.baseBranch + " 时和它上面的新提交有冲突（" + String.join("、", files) + "），正在自动解决，解决后重新验证和审查");
        run.save();
    }

    /** What a developer's go at the conflicts left: its summary, and the commit it was on (null: a fix after the rebase). */
    private record Attempt(String summary, String commit) {
    }

    /**
     * Rebases onto the base branch and has the developer resolve each conflicted commit, in its own conversation; then
     * checks the result. A go that doesn't pass goes back with the reason, {@link #RESOLVE_ROUNDS} at most per commit;
     * then, or when the two sides contradict each other, the rebase is undone and the user decides.
     */
    private void resolve(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) {
        HarnessState s = run.state();
        if (answer != null && !CodingPrompts.bareApproval(answer)) {
            s.conflictHint = answer;
        }
        String title = run.task().title();
        run.begin(step, "解决冲突 · 第 " + (s.conflicts.size() + 1) + " 轮");
        Workspace.Conflict c = ws.startRebase(s);
        run.save();
        List<String> files = new ArrayList<>(c != null ? c.files() : lastFiles(s));
        StringBuilder snippets = new StringBuilder();
        String feedback = null;
        int round = 0;
        boolean fixing = false;
        boolean headed = true; // begin() headed the first round
        while (true) {
            List<HarnessState.PlatformCheck> checks = new ArrayList<>();
            Attempt attempt = new Attempt(null, null);
            if (c != null || fixing) {
                if (round == RESOLVE_ROUNDS) {
                    giveUp(run, step, ws, files, feedback);
                    return;
                }
                round++;
                if (!headed) {
                    heading(run, s);
                }
                headed = false;
                String prompt = c != null ? CodingPrompts.resolve(title, s, c, feedback, round)
                        : CodingPrompts.resolveFix(s, feedback, round);
                HarnessHost.Turn turn = turn(run, prompt, RESOLVE);
                if (turn.cancelled()) {
                    run.stopped(step); // a rebase under way is picked up where it stopped
                    return;
                }
                attempt = new Attempt(firstLine(turn.text()), c == null ? null : c.commit());
                if (CodingPrompts.intentClash(turn.text())) {
                    ws.rollbackRebase(s);
                    String why = "两边的改动意图互相矛盾，需要你决定";
                    recordConflict(run, attempt, files, checks, null, false, why);
                    run.waitFor(step, clashMessage(s, turn.text()));
                    return;
                }
                if (c != null) {
                    c.hunks().forEach((f, h) -> snippets.append("\n### ").append(f).append("\n```\n").append(h).append("```\n"));
                    List<String> left = ws.markers(s, c.files());
                    checks.add(new HarnessState.PlatformCheck("冲突标记", left.isEmpty(),
                            left.isEmpty() ? "没有遗留" : "还有冲突标记：" + String.join("、", left)));
                    if (!left.isEmpty()) {
                        feedback = "这些文件里还有冲突标记（<<<<<<< / ======= / >>>>>>>）：" + String.join("、", left);
                        recordConflict(run, attempt, files, checks, null, false, feedback);
                        continue;
                    }
                    Workspace.Continued k = ws.continueRebase(s);
                    run.save();
                    if (k.next() != null) { // this commit went in; the next one conflicts too
                        checks.add(new HarnessState.PlatformCheck("继续变基", true, "提交 " + c.commit() + " 已应用；下一个提交 "
                                + k.next().commit() + " 也有冲突"));
                        recordConflict(run, attempt, files, checks, null, true, null);
                        c = k.next();
                        k.next().files().stream().filter(f -> !files.contains(f)).forEach(files::add);
                        round = 0;
                        feedback = null;
                        continue;
                    }
                    if (!k.done()) {
                        checks.add(new HarnessState.PlatformCheck("继续变基", false, k.error()));
                        feedback = "git rebase --continue 没有成功：" + k.error();
                        recordConflict(run, attempt, files, checks, null, false, feedback);
                        c = ws.startRebase(s);
                        continue;
                    }
                    checks.add(new HarnessState.PlatformCheck("继续变基", true, "变基完成，接到了 " + s.baseBranch
                            + " @ " + CodingPrompts.shortSha(s.baseSha)));
                    c = null;
                } else {
                    ws.commitAll(s, "解决冲突后修正（第 " + round + " 轮）");
                }
            }
            headed = false;
            ws.recordChanges(s);
            HarnessState.Round v = check(run, s, ws);
            checks.add(new HarnessState.PlatformCheck("验证命令", v.passed(),
                    v.passed() ? "第 " + v.round() + " 轮全部通过" : "没通过：" + failing(v)));
            String verdict = null;
            String reason = null;
            if (v.passed() && files.isEmpty()) {
                // nothing was resolved (the rebase went cleanly): the checks are all there is to judge
            } else if (!v.passed()) {
                StringBuilder why = new StringBuilder("验证命令没有通过：");
                v.checks().stream().filter(x -> x.exitCode() != 0).forEach(x -> why.append("\n命令：").append(x.command())
                        .append(" → 退出码 ").append(x.exitCode()).append("\n输出末尾：\n").append(Git.tail(x.logTail(), 2000)));
                reason = why.toString();
            } else {
                Looked look = look(run, ws, CodingPrompts.resolveReview(title, s, files, snippets.toString(),
                        ws.diff(s, files, 60_000)), RESOLVE_REVIEW);
                if (look.turn().cancelled()) {
                    run.stopped(step);
                    return;
                }
                CodingPrompts.Review r = CodingPrompts.parseReview(look.turn().text());
                List<HarnessState.Finding> blocking = r.findings().stream().filter(HarnessState.Finding::blocking).toList();
                verdict = r.verdict();
                boolean ok = blocking.isEmpty() && "PASS".equals(verdict);
                checks.add(new HarnessState.PlatformCheck("审查", ok, look.model() + " · VERDICT: " + verdict
                        + (blocking.isEmpty() ? "" : " · " + blocking.size() + " 个问题")));
                if (!ok) {
                    StringBuilder why = new StringBuilder("审查（").append(look.model()).append("）认为解决得不对：");
                    blocking.forEach(f -> why.append("\n- [").append(CodingPrompts.label(f.severity())).append("] ")
                            .append(f.text()).append(f.where() == null ? "" : "（" + f.where() + "）"));
                    if (blocking.isEmpty()) {
                        why.append("没有给出 `VERDICT: PASS`");
                    }
                    reason = why.toString();
                }
            }
            recordConflict(run, attempt, files, checks, verdict, reason == null, reason);
            if (reason == null) {
                ws.keepRebase(s);
                run.done(step, "解决了 " + files.size() + " 个文件的冲突：" + String.join("、", files));
                return;
            }
            feedback = reason;
            fixing = true;
        }
    }

    /** A later round's heading in the run log ("── 解决冲突 · 第 2 轮 ──"). */
    private static void heading(HarnessRun run, HarnessState s) {
        run.note("── 解决冲突 · 第 " + (s.conflicts.size() + 1) + " 轮 ──");
    }

    private void recordConflict(HarnessRun run, Attempt a, List<String> files, List<HarnessState.PlatformCheck> checks,
                                String verdict, boolean passed, String reason) {
        HarnessState s = run.state();
        s.conflicts = new ArrayList<>(s.conflicts);
        s.conflicts.add(new HarnessState.ConflictRound(s.conflicts.size() + 1, Instant.now(),
                a.summary() == null ? null : run.task().model(), a.commit(), List.copyOf(files), a.summary(),
                List.copyOf(checks), verdict, passed, reason));
        run.note((passed ? "✓ " : "✕ ") + "解决冲突第 " + s.conflicts.size() + " 轮" + (passed ? "通过" : "没通过：" + firstLine(reason)));
        run.save();
    }

    private static List<String> lastFiles(HarnessState s) {
        return s.conflicts.isEmpty() ? List.of() : s.conflicts.get(s.conflicts.size() - 1).files();
    }

    /**
     * Out of rounds: the branch goes back to how it was before the rebase (a rebase under way aborted, one that went
     * through and the fixes after it undone) and the user decides.
     */
    private static void giveUp(HarnessRun run, HarnessState.Step step, Workspace ws, List<String> files, String reason) {
        HarnessState s = run.state();
        ws.rollbackRebase(s);
        run.save();
        run.waitFor(step, "解决和 " + s.baseBranch + " 的冲突试了 " + RESOLVE_ROUNDS + " 轮还没通过，没有合并。\n\n**冲突的文件**："
                + String.join("、", files) + "\n\n**最后一轮没通过的原因**\n" + Git.tail(reason, 1500)
                + "\n\n可以回复怎么解（比如「以 " + s.baseBranch + " 为准」「保留双方，……」），它会照这个再试；也可以停止任务。"
                + "\n\n👉 下一步：回复「继续」再试 " + RESOLVE_ROUNDS + " 轮");
    }

    private static String clashMessage(HarnessState s, String text) {
        String said = text.lines().filter(l -> !CodingPrompts.intentClash(l)).collect(java.util.stream.Collectors.joining("\n")).strip();
        return "合并到 " + s.baseBranch + " 时有冲突，而且两边的改动意图互相矛盾，开发模型没有硬合，等你决定：\n\n"
                + Git.tail(said, 3000) + "\n\n👉 下一步：回复你的决定（比如「以 " + s.baseBranch + " 为准」「保留双方」），会照它重新解决";
    }

    // --- checks ---

    /** Runs every verify command in the workspace; passing means every one exited 0. */
    private HarnessState.Round check(HarnessRun run, HarnessState s, Workspace ws) {
        Git git = new Git(env());
        List<HarnessState.Check> checks = new ArrayList<>();
        for (String cmd : s.verifyCommands) {
            run.note("▶ " + cmd);
            Git.Result r = git.shell(Path.of(s.dir), cmd, checkTimeoutSeconds);
            checks.add(new HarnessState.Check(cmd, r.exit(), r.millis(), Git.tail(r.output(), 4000)));
            run.note((r.ok() ? "✓ " : "✕ ") + cmd + " · 退出码 " + r.exit() + " · " + seconds(r.millis()));
            if (run.cancelled()) {
                break;
            }
        }
        boolean passed = !checks.isEmpty() && checks.stream().allMatch(c -> c.exitCode() == 0);
        HarnessState.Round round = new HarnessState.Round(s.rounds.size() + 1, Instant.now(), passed, checks, null);
        s.rounds = new ArrayList<>(s.rounds);
        s.rounds.add(round);
        run.save();
        return round;
    }

    // --- helpers ---

    private HarnessHost.Turn turn(HarnessRun run, String prompt, String label) {
        HarnessState s = run.state();
        boolean first = !s.conversationStarted;
        HarnessHost.Turn t = run.turn(prompt, s.conversation, first, label);
        s.conversationStarted = true;
        run.save();
        return t;
    }

    /** The owner's token for this remote, if one may be sent there (see {@link PullRequests#connectorFor}). */
    private record Auth(String connector, String token) {
    }

    private static Auth remoteToken(HarnessHost host, String owner, HarnessState s) {
        String gitlab = host.connectorSite(PullRequests.GITLAB, owner).orElse(null);
        String connector = PullRequests.connectorFor(s.repo, gitlab);
        String token = connector == null ? null : host.connectorToken(connector, owner).orElse(null);
        return new Auth(connector, token);
    }

    /**
     * The credentials for a remote: only an https repository on a code host the owner authorized (github.com, or the
     * GitLab site their token was verified on), with their own token. Never the server's own keys (ssh), never an
     * arbitrary host, and git may use no other protocol (a redirect or submodule can't switch to file:// or ssh).
     */
    private static Map<String, String> remoteAuth(HarnessHost host, String owner, HarnessState s) {
        if (!s.mode.equals("remote")) {
            return Map.of(); // a local repository under the admin's directories
        }
        String lower = s.repo.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) {
            throw new IllegalArgumentException("仓库地址用的是 http，令牌会明文传输：请改用 https 地址");
        }
        if (!lower.startsWith("https://")) {
            throw new IllegalArgumentException("远程仓库只支持 https 地址（不用服务器自己的 ssh 密钥）：请改成 https://… 的地址");
        }
        Auth a = remoteToken(host, owner, s);
        if (a.connector() == null) {
            throw new IllegalArgumentException("只能用 GitHub，或你在「连接」页授权过的 GitLab 上的仓库；" + Workspace.host(s.repo)
                    + " 不在其中。是公司的 GitLab 的话，请在「连接」页的 GitLab 里填它的地址（" + PullRequests.siteOf(s.repo) + "）和你的个人访问令牌");
        }
        if (a.token() == null) {
            throw new IllegalArgumentException(a.connector().equals(PullRequests.GITHUB)
                    ? "用 GitHub 仓库需要你的授权：请到「连接」页授权 GitHub"
                    : "用这个 GitLab 的仓库需要你的授权：请到「连接」页的 GitLab 里填地址和个人访问令牌");
        }
        Map<String, String> env = new java.util.HashMap<>(Workspace.auth(PullRequests.siteOf(s.repo), a.connector(), a.token()));
        env.put("GIT_ALLOW_PROTOCOL", "https");
        return env;
    }

    /** The spec as the user approves it in the chat: goal, criteria, commands, then the answer that approves it. */
    static String specSummary(HarnessState s) {
        StringBuilder sb = new StringBuilder("规格写好了，请确认。\n\n");
        String goal = CodingPrompts.section(s.spec, "目标").strip();
        if (!goal.isEmpty()) {
            sb.append("**目标**：").append(goal.length() > 300 ? goal.substring(0, 300) + "…" : goal).append("\n\n");
        }
        sb.append("**验收标准**\n");
        for (int i = 0; i < s.criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(s.criteria.get(i).text()).append("\n");
        }
        sb.append("\n**验证命令**（平台自己跑，以退出码为准）\n");
        s.verifyCommands.forEach(c -> sb.append("- `").append(c).append("`\n"));
        return sb.append("\n完整规格在任务的「规格」里。要改就直接说改哪里。\n\n👉 下一步：回复「确认规格」开始开发").toString();
    }

    /** Every step from {@code key} on runs again (a change after it was checked must be checked again). */
    private static void resetFrom(HarnessState s, String key) {
        boolean after = false;
        for (HarnessState.Step st : s.steps) {
            after |= st.key.equals(key);
            if (after) {
                st.status = Status.PENDING;
                st.startedAt = null;
                st.finishedAt = null;
                st.note = null;
            }
        }
    }

    private static void replaceLast(HarnessState s, HarnessState.Round r) {
        List<HarnessState.Round> rounds = new ArrayList<>(s.rounds);
        rounds.set(rounds.size() - 1, r);
        s.rounds = rounds;
    }

    private static String failing(HarnessState.Round r) {
        return String.join("、", r.checks().stream().filter(c -> c.exitCode() != 0).map(HarnessState.Check::command).toList());
    }

    private static String commitMessage(Task task, HarnessState s) {
        StringBuilder sb = new StringBuilder(task.title()).append("\n\n");
        s.criteria.forEach(c -> sb.append("- ").append(c.text()).append("\n"));
        return sb.append("\nSuperAgent task ").append(task.id()).toString();
    }

    private static String prBody(Task task, HarnessState s) {
        StringBuilder sb = new StringBuilder("## 规格\n\n").append(s.spec == null ? "" : s.spec).append("\n\n## 验证\n\n");
        HarnessState.Round last = s.rounds.isEmpty() ? null : s.rounds.get(s.rounds.size() - 1);
        if (last != null) {
            last.checks().forEach(c -> sb.append("- `").append(c.command()).append("` → 退出码 ").append(c.exitCode()).append("\n"));
        }
        sb.append("\n## 审查\n\n");
        s.findings.forEach(f -> sb.append("- [").append(CodingPrompts.label(f.severity())).append("] ").append(f.text())
                .append("（").append(f.state()).append("）\n"));
        return sb.append("\n由 SuperAgent 任务 ").append(task.id()).append(" 生成").toString();
    }

    /** The task's delivered result, as its output. */
    private static String output(Task task, HarnessState s) {
        StringBuilder sb = new StringBuilder("## ").append(task.title()).append("\n\n");
        sb.append(s.deliveryNote == null ? "已完成" : s.deliveryNote);
        if (s.deliveryResult != null && s.deliveryResult.startsWith("http")) {
            sb.append("\n\n").append(s.deliveryResult);
        }
        sb.append("\n\n### 改动\n");
        s.files.forEach(f -> sb.append("- `").append(f.path()).append("` +").append(f.added()).append(" −").append(f.removed()).append("\n"));
        sb.append("\n### 验收标准\n");
        s.criteria.forEach(c -> sb.append(c.met() ? "- ✅ " : "- ⬜ ").append(c.text())
                .append(c.evidence() == null ? "" : "（" + c.evidence() + "）").append("\n"));
        if (!s.reviews.isEmpty()) {
            sb.append("\n### 审查和验收\n");
            s.reviews.forEach(r -> sb.append("- 第 ").append(r.round()).append(" 轮").append(r.kind().equals(ACCEPT) ? "验收" : "审查")
                    .append("（").append(r.model()).append("）：").append(r.passed() ? "通过" : r.blocking() + " 个问题交回修复").append("\n"));
            List<HarnessState.Finding> tips = s.findings.stream().filter(f -> f.severity().equals("low")).toList();
            if (!tips.isEmpty()) {
                sb.append("\n### 建议（没有要求修改）\n");
                tips.forEach(f -> sb.append("- ").append(f.text()).append(f.where() == null ? "" : "（" + f.where() + "）").append("\n"));
            }
        }
        HarnessState.Round last = s.rounds.isEmpty() ? null : s.rounds.get(s.rounds.size() - 1);
        if (last != null) {
            sb.append("\n### 验证（第 ").append(last.round()).append(" 轮）\n");
            last.checks().forEach(c -> sb.append("- `").append(c.command()).append("` → ").append(c.exitCode() == 0 ? "通过" : "失败")
                    .append("\n"));
        }
        return sb.toString();
    }

    private Map<String, String> env() {
        String path = cliPaths == null ? System.getenv("PATH") : String.join(File.pathSeparator, cliPaths.searchPath());
        return Map.of("PATH", path == null ? "" : path);
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static String seconds(long ms) {
        return ms < 60_000 ? String.format("%.1fs", ms / 1000.0) : (ms / 60_000) + "m" + (ms % 60_000 / 1000) + "s";
    }

    /** A repository named in a reply: an absolute path or a git address. */
    private static String repoIn(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(https?://[^\\s，。、；]+|(?<![\\w.])/[^\\s，。、；]+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
