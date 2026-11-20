package com.superagent.task.harness.article;

import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.skill.Skill;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.skills.GenreSeeds;
import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessRun;
import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Not a test of the code: runs the real harness (the built-in genre packs) with the Claude Code CLI as the author and
 * the reviewers, on a small topic from this repository. Only with {@code -Dsa.real=research|design}; the output goes
 * under {@code -Dsa.out}. Stand-ins for what the platform does around it: the user's answers are given by this class
 * (outline and questions are confirmed), and the author writes the draft with its Write tool instead of file_write.
 */
@EnabledIfSystemProperty(named = "sa.real", matches = "research|design")
class RealRunDriver {

    private static final Path REPO = Path.of("").toAbsolutePath().getParent();
    private static final String TOOLS = "Read,Grep,Glob,Write,Bash(grep:*),Bash(rg:*),Bash(cat:*),Bash(head:*),Bash(tail:*),Bash(ls:*),Bash(wc:*),Bash(find:*),"
            + "Bash(git log:*),Bash(git show:*),Bash(git blame:*),Bash(git grep:*),Bash(git diff:*),Bash(git ls-files:*)";

    @Test
    void run() throws Exception {
        boolean research = "research".equals(System.getProperty("sa.real"));
        Path out = Path.of(System.getProperty("sa.out", "/tmp/sa-real/" + System.getProperty("sa.real")));
        Path ws = Files.createDirectories(out.resolve("ws"));
        String genre = research ? GenreSeeds.RESEARCH : GenreSeeds.DESIGN;
        String title = research ? "Mermaid 语法校验：现有 Java 轻量校验够不够，要不要换官方 parser" : "SuperAgent 的 assign_task 和 start_task 支持指定写作文体";
        String brief = research ? RESEARCH_BRIEF.formatted(REPO) : DESIGN_BRIEF.formatted(REPO);

        Instant now = Instant.now();
        List<Skill> skills = new ArrayList<>();
        for (var seed : GenreSeeds.ALL) {
            skills.add(new Skill(UUID.randomUUID().toString(), seed.name(), seed.description(), seed.category(), seed.content(), "company", true, false, "system", "system", now, now));
        }
        ArticleHarness harness = new ArticleHarness(url -> new ArticleChecks.LinkStatus(200, null), new GenrePacks(() -> skills));
        Task task = new Task("real-" + genre, title, brief, TaskSource.WEB_CHAT, REPO.toString(), null, "article", TaskStatus.RUNNING, 0, BigDecimal.ZERO,
                null, AgentType.CLAUDE, ws.toString(), "ada", null, now, now, null, now, null);
        Host host = new Host(ws, out);
        HarnessState s = harness.start(task, null, genre);
        s.conversation = UUID.randomUUID().toString();
        String reply = null;
        for (int i = 0; i < 12; i++) {
            host.status = null;
            harness.drive(new HarnessRun(host, task, s, reply, new Cancellation()));
            host.log("== drive " + i + " → " + host.status + (s.waitingOn == null ? "" : " waiting on " + s.waitingOn));
            if (host.status != TaskStatus.NEEDS_CONFIRMATION) {
                break;
            }
            reply = switch (s.waitingOn == null ? "" : s.waitingOn) {
                case ArticleHarness.OUTLINE -> "确认提纲";
                case ArticleHarness.BRIEF, ArticleHarness.RESEARCH -> "确认";
                default -> null;
            };
            if (reply == null) {
                host.log("== stopped: the run waits on " + s.waitingOn + " (a person would decide)");
                break;
            }
            host.log("== the user's answer (given by the driver): " + reply);
        }
        Files.writeString(out.resolve("state.json"), s.toJson());
        host.log("== final status " + host.status);
    }

    private static final String RESEARCH_BRIEF = """
            写一份技术调研：SuperAgent 的写作 harness 要在平台里检查文档里的 Mermaid 图，现在用 Java 里写的轻量结构校验（见 MermaidCheck），
            够不够用？要不要换成 Mermaid 官方的 parser？候选方案：A 保持现在的 Java 轻量校验；B 调用官方 mermaid parser（Node 子进程）；
            C 不在后端校验，只靠前端渲染时报错。评估维度：能抓出的错误种类、对部署环境的要求（Java 服务是否要装 Node）、速度、维护成本、误报风险。
            读者：负责后端的工程师。目的：读完能决定下一步要不要换。目标 3000 字。
            代码位置：%s（只读）。资料：仓库里的 superagent-task/src/main/java/com/superagent/task/harness/article/MermaidCheck.java、
            TechChecks.java 和对应的测试 TechChecksTest.java，以及 superagent-ui/src/main/resources/static/vendor/mermaid.min.js 的大小。
            """;
    private static final String DESIGN_BRIEF = """
            写一份技术方案：SuperAgent 里有两个让 SuperAgent 给团队成员派活的工具（assign_task 和 start_task），现在它们建写作任务时没法指定文体，
            只能落到默认的通用长文。方案要让它们也能指定写作文体（genre），和聊天里 create_task 的行为一致。
            读者：后端工程师，要能照着实现。目标 3000 字。
            代码位置：%s。要看的代码：superagent-orchestrator 里的 SuperAgentTools、SuperAgentTaskTools、SuperAgentService 的 assign / startTask，
            superagent-task 里的 TaskKinds、TaskMcpTools（create_task 怎么传 genre）、TaskService.create。
            """;

    private static final class Host implements HarnessHost {
        final Path ws, out;
        final List<String> started = new ArrayList<>();
        TaskStatus status;
        int turns;

        Host(Path ws, Path out) {
            this.ws = ws;
            this.out = out;
        }

        void log(String line) {
            System.out.println(line);
            try {
                Files.writeString(out.resolve("run.log"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private String claude(String prompt, String session, boolean resume, String model, String label) {
            log("-- turn " + (++turns) + " [" + label + "] " + (model == null ? "default model" : model) + (resume ? " (resumed)" : " (new session)"));
            try {
                List<String> cmd = new ArrayList<>(List.of("claude", "-p", resume ? "--resume" : "--session-id", session, "--output-format", "text",
                        "--permission-mode", "acceptEdits", "--allowedTools", TOOLS, "--add-dir", REPO.toString()));
                if (model != null) {
                    cmd.addAll(List.of("--model", model));
                }
                Process p = new ProcessBuilder(cmd).directory(ws.toFile()).redirectErrorStream(true).start();
                p.getOutputStream().write(prompt.getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().close();
                String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                Files.writeString(out.resolve(String.format("turn-%02d-%s.txt", turns, label)), "## prompt\n" + prompt + "\n\n## reply\n" + text);
                return text;
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation c) {
            boolean file = label.equals(ArticleHarness.DRAFT) || label.equals(ArticleHarness.REVISE);
            String full = prompt + (file ? "\n\n（这次运行没有 file_write 工具：用 Write 工具把完整的 Markdown 稿件写到 " + ws.resolve("draft.md")
                    + "，每次都覆盖这个文件；其余要求不变，回复里不要贴全文。）" : "");
            boolean resume = started.contains(conversation);
            started.add(conversation);
            return new Turn(claude(full, conversation, resume, null, label), BigDecimal.ZERO, false);
        }

        @Override
        public Turn turnOn(Task task, Model model, String prompt, String conversation, String label, Cancellation c) {
            return new Turn(claude(prompt, conversation, false, model == null ? null : model.model(), label), BigDecimal.ZERO, false);
        }

        @Override public Optional<Model> checkerModel(Task task) { return Optional.of(new Model("sonnet", "claude")); }
        @Override public List<Produced> produced(Task task) {
            Path f = ws.resolve("draft.md");
            try {
                return Files.isRegularFile(f) ? List.of(new Produced("draft", "draft.md", "text/markdown", Files.size(f), Files.readString(f))) : List.of();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public String brief(Task task, boolean first) { return task.brief(); }
        @Override public Task workIn(Task task, String dir) { return task; }
        @Override public void progress(Task task, int percent) { }
        @Override public void note(Task task, String text) { log(text); }
        @Override public void ask(Task task, String text) { log("[ask the user]\n" + text); }
        @Override public void tell(Task task, String text) { log("[tell] " + text); }
        @Override public void save(Task task, HarnessState state) { }
        @Override public void settle(Task task, TaskStatus s, int progress, String output, String error) {
            status = s;
            if (error != null) {
                log("[error] " + error);
            }
        }
        @Override public Optional<String> connectorToken(String connectorId, String owner) { return Optional.empty(); }
        @Override public Optional<String> connectorSite(String connectorId, String owner) { return Optional.empty(); }
        @Override public List<Path> localRoots() { return List.of(); }
        @Override public Path workRoot() { return Path.of("."); }
    }
}
