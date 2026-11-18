package com.superagent.task.harness.coding;

import com.superagent.core.task.Task;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The coding flow: intake → workspace → spec (the user approves it) → develop → verify (the platform runs the checks;
 * failures go back to the agent, a few rounds at most) → independent review (a fresh conversation that never saw the
 * development) → delivery (squash-merged into the local branch, or pushed with a pull request).
 */
@Component
public class CodingHarness implements TaskHarness {

    private static final Logger log = LoggerFactory.getLogger(CodingHarness.class);

    static final String INTAKE = "intake", WORKSPACE = "workspace", SPEC = "spec", DEVELOP = "develop",
            VERIFY = "verify", REVIEW = "review", DELIVER = "deliver";
    static final int FIX_ROUNDS = 3;
    static final int REVIEW_ROUNDS = 2;

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
                new HarnessState.Step(DELIVER, "交付")));
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
                    case DEVELOP -> develop(run, step, ws);
                    case VERIFY -> verify(run, step, ws, answer);
                    case REVIEW -> review(run, step, ws, answer);
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
        if (answer != null && s.mode.equals("local") && s.delivery.equals("pr")) {
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

    private void develop(HarnessRun run, HarnessState.Step step, Workspace ws) {
        HarnessState s = run.state();
        run.begin(step);
        HarnessHost.Turn turn = turn(run, CodingPrompts.develop(s), DEVELOP);
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
        if (answer != null && CodingPrompts.approves(answer)) {
            run.note("你确认可以交付，跳过剩下的审查意见");
            run.done(step, "你确认交付");
            return;
        }
        run.begin(step);
        s.reviewRounds++;
        // A new conversation every time: the reviewer never sees how the code came about.
        HarnessHost.Turn turn = run.turn(CodingPrompts.review(run.task().title(), s, ws.diff(s, 80_000)),
                UUID.randomUUID().toString(), true, REVIEW);
        if (turn.cancelled()) {
            run.stopped(step);
            return;
        }
        CodingPrompts.Review r = CodingPrompts.parseReview(turn.text(), s.criteria);
        List<HarnessState.Finding> kept = new ArrayList<>(s.findings.stream().filter(f -> !"open".equals(f.state())).toList());
        kept.addAll(r.findings());
        s.findings = kept;
        s.criteria = r.criteria();
        List<HarnessState.Finding> toFix = r.findings().stream().filter(f -> !f.severity().equals("low")).toList();
        if (r.passed() && toFix.isEmpty()) {
            s.findings = s.findings.stream().map(f -> "open".equals(f.state()) ? f.withState("ignored") : f).toList();
            run.done(step, "通过" + (r.findings().isEmpty() ? "" : "（" + r.findings().size() + " 条建议）"));
            return;
        }
        if (s.reviewRounds >= REVIEW_ROUNDS + 1) {
            run.waitFor(step, "审查了 " + REVIEW_ROUNDS + " 轮仍有问题没解决（见任务的「审查」）。也可以回复修改意见再改一轮。"
                    + "\n\n👉 下一步：回复「确认交付」照现在的样子交付");
            return;
        }
        run.tell("独立审查发现 " + toFix.size() + " 个要改的问题，正在修复，修完重新验证和审查");
        HarnessHost.Turn fix = turn(run, CodingPrompts.fixReview(toFix), REVIEW);
        if (fix.cancelled()) {
            run.stopped(step);
            return;
        }
        ws.commitAll(s, "按审查意见修改");
        ws.recordChanges(s);
        s.findings = s.findings.stream().map(f -> toFix.contains(f) ? f.withState("fixed") : f).toList();
        s.fixBudget = FIX_ROUNDS;
        resetFrom(s, VERIFY); // the fix is checked again, then reviewed again
        run.save();
    }

    private void deliver(HarnessRun run, HarnessState.Step step, Workspace ws, String answer) throws IOException {
        HarnessState s = run.state();
        run.begin(step);
        String message = commitMessage(run.task(), s);
        if (s.delivery.equals("merge") && s.mode.equals("local")) {
            Workspace.Merge m = ws.mergeLocal(s, message, () -> check(run, s, ws).passed());
            if (m.problem() != null) {
                run.waitFor(step, m.problem());
                return;
            }
            s.deliveryResult = m.commit();
            s.deliveryNote = "已合并进 " + s.baseBranch + "（提交 " + CodingPrompts.shortSha(m.commit()) + "）"
                    + (m.rebased() ? "，合并前变基到了最新提交并重新验证" : "");
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

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
