package com.superagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDto;
import com.superagent.core.link.UnreadableMaterialsException;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.task.harness.article.GenrePacks;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.str;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/** Lets a chat agent create, follow and steer tasks. A task it creates belongs to the chatting user and inherits the agent's skills. */
@Component
public class TaskMcpTools implements McpToolProvider {

    private final TaskService tasks;
    private final GenrePacks genres;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskMcpTools(TaskService tasks, GenrePacks genres) {
        this.tasks = tasks;
        this.genres = genres;
    }

    public TaskMcpTools(TaskService tasks) {
        this(tasks, GenrePacks.none());
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("create_task", "把要动手做的事作为任务异步执行：读文件或资料、上网查证、产出交付物、多步操作、可能运行很久的事。只有用户确认了你列出的任务参数表后才能调用，绝不跳过确认。任务由你自己执行（带着你的人设、记忆和技能），不占住对话；完成、失败或需要用户确认时，结果会自动发回这个对话。",
                        props(req("title", "string", "任务标题"), req("brief", "string", "任务说明：目标、范围、验收标准"),
                                opt("repo", "string", "编码任务的代码位置：这台服务器上的本地仓库路径，或 git 仓库地址（https://…）"),
                                opt("branch", "string", "从哪个分支开始：本地仓库不填就用它当前所在的分支；远程仓库不填就用默认分支"),
                                opt("delivery", "string", "编码任务做完怎么交付：merge = 合并回本地分支（本地仓库默认）；pr = 推送新分支并开 PR/MR（远程仓库默认）"),
                                req("kind", "string", TaskKinds.DESCRIPTION),
                                opt("genre", "string", TaskKinds.GENRE_DESCRIPTION),
                                opt("proxy_confirm_id", "string", "proxy_confirm_params 返回已代确认时给的记录 id；其他情况不填"),
                        opt("continues_from", "string", "接着哪个任务做（任务 id，用 list_tasks 查）：新任务会等它完成，拿到它的结论，在它的基础上继续，而不是从头来"),
                                opt("after", "string", TaskWaits.DESCRIPTION),
                                opt("materials", "string", "要读的外部资料链接（参数表「资料」那一行里的链接），多个用换行分开；"
                                        + "建任务前会逐个读，读不到就不建任务并说明原因。只是要操作的网址（比如要发布到的后台）不要放这里。不填则检查说明里的所有链接")),
                        "title", "brief", "kind"),
                tool("coding_targets", "编码任务能在哪里做：你所在项目配置的仓库（优先用）、允许的本地代码目录和其中的 git 仓库、用户最近用过的代码位置、是否授权了 GitHub/GitLab。"
                        + "列编码任务的参数表之前先调用，用它填「代码位置」并给出可选项。", props(), (String[]) null),
                tool("article_genres", "写作任务有哪些文体可选：公司的文体包（名字、适用场景、是否要读代码仓库）。"
                        + "列写作任务的参数表之前先调用，按内容选一个填进「文体」一行；没有合适的就选「通用长文」。", props(), (String[]) null),
                tool("subtitle_models", "字幕任务这次会用哪些模型：听写模型（几个、哪些、哪个带时间轴）和校准模型（几个、哪些），以及服务器有没有 ffmpeg（没有时音频的大小和格式限制）。"
                        + "列字幕任务的参数表之前先调用，把结果填进「听写模型」「校准模型」「ffmpeg」三行；没有可用的听写模型时如实告诉用户要管理员先配置。",
                        props(), (String[]) null),
                tool("list_tasks", "列出当前用户的任务：状态、进度、代码仓库，以及在等哪些任务完成。", props(), (String[]) null),
                tool("get_task", "查看某个任务的状态与进度，以及它在等哪些任务。", props(req("id", "string", "任务 id")), "id"),
                tool("run_task_now", "不等了，直接跑：它等的任务失败或被停止、因此被阻塞的任务，马上开始（只是还在等的任务会自动开始，不能直接跑）。只在用户要求时调用。",
                        props(req("id", "string", "任务 id")), "id"),
                tool("send_task_message", "给任务发送消息：回答确认、或变更/指挥其执行（任务非运行中时）。",
                        props(req("id", "string", "任务 id"), req("content", "string", "要发送的内容")), "id", "content"),
                tool("proxy_confirm_params", "列好任务参数表、还没问用户之前调用：看用户有没有开「代我确认」让 SuperAgent 代他确认这张表。"
                                + "返回「已代确认」就直接调用 create_task，并在回复里说明是按用户的规则代确认的；返回别的就照常把表给用户、等用户确认。",
                        props(req("title", "string", "任务标题"), req("open_params", "number", "表格里还标着 ❓ 的参数个数，没有就填 0"),
                                opt("kind", "string", "表格里的任务类型（和 create_task 的 kind 一致）"),
                                opt("continues_from", "string", "接着哪个任务做；和 create_task 的 continues_from 一致")),
                        "title", "open_params"),
                tool("stop_task", "停止正在运行的任务。", props(req("id", "string", "任务 id")), "id"),
                tool("delete_task", "删除一个任务。", props(req("id", "string", "任务 id")), "id"));
    }

    /** What creating with these arguments is: carrying on a task, a complex task that splits, or an ordinary task. */
    private static com.superagent.core.proxyconfirm.ConfirmType confirmType(JsonNode args) {
        return strOrNull(args, "continues_from") != null ? com.superagent.core.proxyconfirm.ConfirmType.CONTINUATION
                : "complex".equals(strOrNull(args, "kind")) ? com.superagent.core.proxyconfirm.ConfirmType.SUBTASK_SPLIT
                : com.superagent.core.proxyconfirm.ConfirmType.TASK_PARAMS;
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String text = switch (name) {
            case "create_task" -> {
                String materials = strOrNull(args, "materials");
                String proxyId = strOrNull(args, "proxy_confirm_id");
                if (proxyId != null && !tasks.proxyClaimable(grant.owner(), proxyId, confirmType(args), str(args, "title"))) {
                    throw new McpToolException("这次创建和代确认的内容对不上（类型或标题不同）：任务没有创建。把参数表给用户，等用户确认后不带 proxy_confirm_id 再创建。");
                }
                TaskDto t;
                try {
                    t = tasks.create(new CreateTaskRequest(
                            str(args, "title"), str(args, "brief"), "web_chat",
                            strOrNull(args, "repo"), strOrNull(args, "branch"), null, null, null,
                            TaskKinds.require(args), null, strOrNull(args, "continues_from"), strOrNull(args, "delivery"),
                            TaskWaits.parse(args), TaskKinds.genre(args)),
                            grant.owner(), grant.agentId(), grant.sessionId(), grant.messageId(),
                            materials == null ? null : List.of(materials));
                    tasks.proxyConfirmCreated(grant.owner(), strOrNull(args, "proxy_confirm_id"), t.id());
                } catch (UnreadableMaterialsException e) {
                    throw new McpToolException(e.getMessage() + "\n把读不到的资料、原因和解决办法照实告诉用户，等用户处理好（授权、改成公开或直接发文件）再重新确认建任务；"
                            + "不要说任务已创建。");
                } catch (TaskNotFoundException e) {
                    throw new McpToolException("任务没有创建：continues_from 或 after 里的任务不存在或你看不到（" + e.getMessage()
                            + "）。用 list_tasks 查到正确的任务 id，更新参数表后再确认；不要说任务已创建。");
                } catch (IllegalArgumentException e) {
                    // A precheck (a coding task's repository, branch, authorization): fixable before anything starts.
                    throw new McpToolException("任务没有创建：" + e.getMessage() + "\n把原因和办法照实告诉用户，按同样格式给出更新后的参数表"
                            + "（有问题的那一行标 ❓），等用户处理好或改了再确认；不要说任务已创建。");
                }
                // The agent does it itself (its character, memory, skills); the result comes back to this chat.
                String how = "complex".equals(t.flow()) ? "它会先拆成子任务同时执行，全部完成后自动汇总。" : "";
                yield "已创建" + TaskKinds.label(t.flow()) + "任务 " + t.id() + "（" + t.title() + "），状态 " + t.status() + "。" + how
                        + "结果出来后会自动发到这个对话里。现在用一两句话告诉用户任务名和要做什么，然后结束这一轮，不要在对话里接着做。";
            }
            case "proxy_confirm_params" -> {
                int open = args.path("open_params").asInt(1);
                var type = confirmType(args);
                String record = tasks.proxyConfirmed(grant.owner(), tasks.projectOfAgent(grant.agentId()), type, str(args, "title"),
                        java.util.Map.of("openParams", open, "clear", open == 0, "why", "参数表里没有待确认项，按你的规则确认"));
                yield record != null ? "已代确认：按用户的规则，这张参数表不用再问用户。现在调用 create_task 创建任务，并带上 proxy_confirm_id=" + record
                        : "没有代确认：照常把参数表给用户，等用户确认。";
            }
            case "coding_targets" -> tasks.codingTargets(grant.owner(), grant.agentId());
            case "article_genres" -> {
                List<String> list = genres.describe();
                yield list.isEmpty() ? "公司还没有文体包，写作任务按通用写法执行。" : "可选的文体：\n- " + String.join("\n- ", list)
                        + "\n没有合适的就选「" + GenrePacks.FALLBACK + "」。";
            }
            case "subtitle_models" -> tasks.subtitleModels();
            case "list_tasks" -> {
                List<TaskDto> list = tasks.list(grant.owner());
                if (list.isEmpty()) {
                    yield "当前没有任务。";
                }
                StringBuilder sb = new StringBuilder("共 " + list.size() + " 个任务：\n");
                for (TaskDto t : list) {
                    sb.append("- ").append(t.id()).append(" | ").append(t.title())
                            .append(" | ").append(t.status()).append(" | ").append(t.progress()).append("%")
                            .append(t.repo() == null ? "" : " | 仓库 " + t.repo()).append(TaskWaits.line(t)).append('\n');
                }
                yield sb.toString();
            }
            case "get_task" -> {
                TaskDto t = tasks.status(require(args, "id"), grant.owner());
                yield t.title() + " — 状态 " + t.status() + "，进度 " + t.progress() + "%"
                        + (t.repo() != null ? "，仓库 " + t.repo() : "")
                        + (t.error() != null ? "，错误：" + t.error() : "") + TaskWaits.detail(t);
            }
            case "run_task_now" -> {
                TaskDto t = tasks.runNow(require(args, "id"), grant.owner());
                yield "任务「" + t.title() + "」不再等其他任务，马上开始。";
            }
            case "send_task_message" -> {
                tasks.sendMessage(require(args, "id"), grant.owner(), str(args, "content"));
                yield "已发送，任务将继续执行。";
            }
            case "stop_task" -> {
                tasks.stop(require(args, "id"), grant.owner());
                yield "已请求停止。";
            }
            case "delete_task" -> {
                tasks.delete(require(args, "id"), grant.owner());
                yield "已删除。";
            }
            default -> throw new McpToolException("Unknown tool: " + name);
        };
        return McpToolResult.ok(text);
    }
}
