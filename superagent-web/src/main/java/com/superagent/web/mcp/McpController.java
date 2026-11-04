package com.superagent.web.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.automation.AutomationDetailDto;
import com.superagent.api.automation.AutomationDto;
import com.superagent.api.automation.AutomationRunDto;
import com.superagent.api.automation.SaveAutomationRequest;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.knowledge.KnowledgeEntryDto;
import com.superagent.api.skill.SkillDto;
import com.superagent.api.skill.SkillRequest;
import com.superagent.api.task.TaskDto;
import com.superagent.core.automation.AutomationSchedule;
import com.superagent.web.automation.AutomationForbiddenException;
import com.superagent.web.automation.AutomationNotFoundException;
import com.superagent.web.automation.AutomationService;
import com.superagent.web.knowledge.KnowledgeConflictException;
import com.superagent.web.superagent.SuperAgentTools;
import com.superagent.web.knowledge.KnowledgeNotFoundException;
import com.superagent.web.knowledge.KnowledgeService;
import com.superagent.web.skill.SkillNotFoundException;
import com.superagent.web.skill.SkillService;
import com.superagent.web.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * SuperAgent's own MCP server, so a chat agent can operate tasks as tools
 * (create / list / get / steer / stop / delete). It speaks the MCP Streamable-HTTP
 * transport by hand: JSON-RPC 2.0 over a single {@code POST /mcp}, each request
 * answered with one {@code application/json} response (no server-initiated stream,
 * so {@code GET /mcp} is 405). Tool calls dispatch into {@link TaskService}.
 *
 * <p>Every call carries a per-turn bearer token from {@link McpGrants}; its grant decides the
 * toolset (chat: task + automation tools; both: knowledge-base and skill tools) and the user it acts for.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final TaskService tasks;
    private final McpGrants grants;
    private final KnowledgeService knowledge;
    private final AutomationService automations;
    private final SkillService skills;
    private final SuperAgentTools superAgentTools;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)");
    private final ObjectMapper mapper = new ObjectMapper();

    public McpController(TaskService tasks, McpGrants grants, KnowledgeService knowledge,
                         AutomationService automations, SkillService skills, SuperAgentTools superAgentTools) {
        this.tasks = tasks;
        this.grants = grants;
        this.knowledge = knowledge;
        this.automations = automations;
        this.skills = skills;
        this.superAgentTools = superAgentTools;
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build(); // no server-initiated SSE
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> rpc(@RequestBody JsonNode body,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        String credential = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : null;
        McpGrants.Grant grant = grants.find(credential).orElse(null);
        if (grant == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean task = grant.kind() == McpGrants.Kind.TASK;
        boolean superAgent = grant.kind() == McpGrants.Kind.SUPER;
        String method = body.path("method").asText("");
        JsonNode id = body.get("id");
        // Notifications (no id) get acknowledged with 202 and no body.
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build();
        }
        try {
            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "ping" -> ok(id, mapper.createObjectNode());
                case "tools/list" -> ok(id, superAgent ? superAgentToolsResult()
                        : task ? knowledgeToolsResult() : chatToolsResult());
                case "tools/call" -> ok(id, superAgent ? callSuperAgentTool(grant, body.path("params"))
                        : task ? callKnowledgeTool(grant, body.path("params"))
                        : callTool(grant, body.path("params")));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (ToolException e) {
            return ok(id, toolError(e.getMessage())); // tool-level error, not a protocol error
        } catch (Exception e) {
            log.warn("mcp method {} failed", method, e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // --- MCP results ---

    /** A SuperAgent round gets only its team / plan / assignment tools (see SuperAgentTools). */
    private ObjectNode superAgentToolsResult() {
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", superAgentTools.list());
        return result;
    }

    private ObjectNode callSuperAgentTool(McpGrants.Grant grant, JsonNode params) {
        SuperAgentTools.Result r = superAgentTools.call(grant, params.path("name").asText(""), params.path("arguments"));
        return textContent(r.text(), r.error());
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "superagent");
        info.put("version", "0.1.0");
        return result;
    }

    private ObjectNode toolsListResult() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("create_task", "创建并立即执行一个任务（文档驱动的长任务）。",
                props(req("title", "string", "任务标题"), req("brief", "string", "任务说明：目标、范围、验收标准"),
                        opt("repo", "string", "可选代码库"), opt("branch", "string", "可选分支"),
                        opt("kind", "string", "可选任务类型：coding/writing/research/stats/general；不填则自动分类")),
                "title", "brief"));
        tools.add(tool("list_tasks", "列出所有任务及其状态。", props(), (String[]) null));
        tools.add(tool("get_task", "查看某个任务的状态与进度。",
                props(req("id", "string", "任务 id")), "id"));
        tools.add(tool("send_task_message", "给任务发送消息：回答确认、或变更/指挥其执行（任务非运行中时）。",
                props(req("id", "string", "任务 id"), req("content", "string", "要发送的内容")), "id", "content"));
        tools.add(tool("stop_task", "停止正在运行的任务。", props(req("id", "string", "任务 id")), "id"));
        tools.add(tool("delete_task", "删除一个任务。", props(req("id", "string", "任务 id")), "id"));
        automationTools(tools);
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    /** Chat agents get the task tools and, for the chatting user, the knowledge-base tools. */
    private ObjectNode chatToolsResult() {
        ObjectNode result = toolsListResult();
        ((ArrayNode) result.get("tools")).addAll((ArrayNode) knowledgeToolsResult().get("tools"));
        return result;
    }

    private ObjectNode knowledgeToolsResult() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("knowledge_list_folders", "列出知识库里已有的全部目录（\"/\" 为根目录）。保存前先看看有没有合适的目录。",
                props(), (String[]) null));
        tools.add(tool("knowledge_create_folder", "在知识库中新建目录（可多级，如 研发规范/需求），已存在则直接返回。",
                props(req("path", "string", "目录路径，用 / 分隔")), "path"));
        tools.add(tool("knowledge_write", "把一段内容（如整理好的笔记、文档的 Markdown）直接写成知识库里的文件。目录不存在会自动创建；同名文件默认自动加序号，不覆盖。",
                props(req("folder", "string", "知识库目标目录，如 笔记/会议；根目录用 \"\""),
                        req("name", "string", "文件名，含扩展名，如 周会纪要.md"),
                        req("content", "string", "文件内容（文本）"),
                        opt("overwrite", "boolean", "仅当用户明确要求覆盖同名文件时设为 true")),
                "folder", "name", "content"));
        tools.add(tool("knowledge_save", "把工作区里的一个文件保存到知识库。目录不存在会自动创建；同名文件默认自动加序号，不覆盖。",
                props(req("file", "string", "工作区内的文件路径（相对工作区，如 output.md）"),
                        req("folder", "string", "知识库目标目录，如 研发规范/需求；根目录用 \"\""),
                        opt("name", "string", "保存后的文件名，默认沿用原文件名"),
                        opt("overwrite", "boolean", "仅当用户明确要求覆盖同名文件时设为 true")),
                "file", "folder"));
        tools.add(tool("skill_list", "列出所有共享 skill（工作规范）：id、名称、分类、适用场景。", props(), (String[]) null));
        tools.add(tool("skill_get", "查看一个 skill 的完整内容和图片列表。", props(req("id", "string", "skill id")), "id"));
        tools.add(tool("skill_create", "新建一个共享 skill。description 要写清楚什么时候使用它，Agent 靠它判断是否适用。",
                props(req("name", "string", "名称，如 写作规范"), req("description", "string", "适用场景，一句话"),
                        opt("category", "string", "分类，如 写作 / 设计 / 运营"),
                        req("content", "string", "正文（Markdown）：规范、步骤、示例；图片用 images/文件名 引用")),
                "name", "description", "content"));
        tools.add(tool("skill_update", "修改一个 skill，只传要改的字段。修改会影响所有用这个 skill 的 Agent，仅在用户要求时使用。",
                props(req("id", "string", "skill id"), opt("name", "string", "新名称"),
                        opt("description", "string", "新的适用场景"), opt("category", "string", "新分类"),
                        opt("content", "string", "新的完整正文（Markdown），会整体替换原正文"),
                        opt("enabled", "boolean", "是否启用")),
                "id"));
        tools.add(tool("skill_add_image", "把工作区里的一张图片（PNG/JPEG/GIF/WebP）加到 skill 中，返回可在正文里引用的路径。",
                props(req("id", "string", "skill id"), req("file", "string", "工作区内的图片路径"),
                        opt("name", "string", "图片名，默认沿用文件名")),
                "id", "file"));
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode callSkillTool(McpGrants.Grant grant, String name, JsonNode args) {
        String user = grant.owner() == null || grant.owner().isBlank() ? "agent" : grant.owner();
        try {
            String text = switch (name) {
                case "skill_list" -> {
                    List<SkillDto> list = skills.list();
                    yield list.isEmpty() ? "还没有任何 skill。" : String.join("\n", list.stream().map(s ->
                            "- " + s.id() + " | " + s.name() + " | " + (s.category() == null ? "未分类" : s.category())
                                    + " | " + (s.description() == null ? "" : s.description())
                                    + (s.enabled() ? "" : "（已停用）")).toList());
                }
                case "skill_get" -> {
                    SkillDto s = skills.get(require(args, "id"));
                    yield "# " + s.name() + "\n分类：" + (s.category() == null ? "未分类" : s.category())
                            + "\n适用场景：" + (s.description() == null ? "" : s.description())
                            + "\n图片：" + (s.images().isEmpty() ? "无" : String.join(", ", s.images()))
                            + "\n\n" + s.content();
                }
                case "skill_create" -> {
                    SkillDto s = skills.create(new SkillRequest(str(args, "name"), str(args, "description"),
                            strOrNull(args, "category"), str(args, "content"), true), user);
                    yield "已创建 skill「" + s.name() + "」，id " + s.id() + "。可在侧边栏 Skills 中查看，并在编辑 Agent 时挂上。";
                }
                case "skill_update" -> {
                    SkillDto s = skills.update(require(args, "id"), new SkillRequest(strOrNull(args, "name"),
                            strOrNull(args, "description"), strOrNull(args, "category"),
                            args.has("content") ? args.path("content").asText("") : null,
                            args.has("enabled") ? args.path("enabled").asBoolean() : null), user);
                    yield "已更新 skill「" + s.name() + "」。";
                }
                case "skill_add_image" -> {
                    Path file = grants.workspaceFile(grant, require(args, "file"));
                    String fileName = strOrNull(args, "name");
                    String stored = skills.addImage(require(args, "id"),
                            fileName != null ? fileName : file.getFileName().toString(), Files.readAllBytes(file), user);
                    yield "已添加图片，在正文中用 ![说明](images/" + stored + ") 引用。";
                }
                default -> throw new ToolException("Unknown tool: " + name);
            };
            return textContent(text, false);
        } catch (IllegalArgumentException | SkillNotFoundException e) {
            throw new ToolException(e.getMessage());
        } catch (IOException e) {
            throw new ToolException("Cannot read the image: " + e.getMessage());
        }
    }

    private ObjectNode callKnowledgeTool(McpGrants.Grant grant, JsonNode params) {
        String name = params.path("name").asText("");
        if (name.startsWith("skill_")) {
            return callSkillTool(grant, name, params.path("arguments"));
        }
        if (grant.owner() == null || grant.owner().isBlank()) {
            throw new ToolException("知识库需要登录用户，本次运行没有关联用户。");
        }
        JsonNode args = params.path("arguments");
        String text;
        try {
            text = switch (name) {
                case "knowledge_list_folders" -> {
                    List<String> folders = knowledge.folders(grant.owner());
                    yield folders.size() == 1 ? "知识库还没有任何目录（只有根目录 /）。"
                            : "知识库目录：\n" + String.join("\n", folders.stream().map(f -> "/" + f).toList());
                }
                case "knowledge_create_folder" ->
                        "目录已就绪：/" + knowledge.ensureFolder(grant.owner(), folder(require(args, "path"))).path();
                case "knowledge_write" -> {
                    KnowledgeEntryDto saved = knowledge.writeText(grant.owner(), folder(str(args, "folder")),
                            require(args, "name"), args.path("content").asText(""), args.path("overwrite").asBoolean(false));
                    log.info("{} {} wrote {} to knowledge base of {}", grant.kind(), grant.taskId(), saved.path(), grant.owner());
                    yield "已保存到知识库：/" + saved.path();
                }
                case "knowledge_save" -> {
                    KnowledgeEntryDto saved = knowledge.saveFile(grant.owner(),
                            grants.workspaceFile(grant, require(args, "file")),
                            folder(str(args, "folder")), strOrNull(args, "name"), args.path("overwrite").asBoolean(false));
                    log.info("{} {} saved {} to knowledge base of {}", grant.kind(), grant.taskId(), saved.path(), grant.owner());
                    yield "已保存到知识库：/" + saved.path();
                }
                default -> throw new ToolException("Unknown tool: " + name);
            };
        } catch (IllegalArgumentException | KnowledgeNotFoundException | KnowledgeConflictException e) {
            throw new ToolException(e.getMessage());
        }
        return textContent(text, false);
    }

    /** Agents tend to write folder paths with a leading or trailing "/". */
    private static String folder(String path) {
        return path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
    }

    private ObjectNode callTool(McpGrants.Grant grant, JsonNode params) {
        String name = params.path("name").asText("");
        if (name.startsWith("knowledge_") || name.startsWith("skill_")) {
            return callKnowledgeTool(grant, params);
        }
        JsonNode args = params.path("arguments");
        if (name.endsWith("_automation") || name.equals("list_automations") || name.equals("get_current_time")) {
            try {
                return textContent(callAutomationTool(grant, name, args), false);
            } catch (IllegalArgumentException | AutomationNotFoundException | AutomationForbiddenException e) {
                throw new ToolException(e.getMessage()); // e.g. a bad cron: the agent can fix it and retry
            }
        }
        String text = switch (name) {
            case "create_task" -> {
                TaskDto t = tasks.create(new CreateTaskRequest(
                        str(args, "title"), str(args, "brief"), "web_chat",
                        strOrNull(args, "repo"), strOrNull(args, "branch"), null, null, null,
                        strOrNull(args, "kind")), grant.owner(), grant.agentId()); // the agent's skills go with it
                yield "已创建任务 " + t.id() + "（" + t.title() + "），状态 " + t.status() + "。可用 get_task 查询进度。";
            }
            case "list_tasks" -> {
                List<TaskDto> list = tasks.list();
                if (list.isEmpty()) {
                    yield "当前没有任务。";
                }
                StringBuilder sb = new StringBuilder("共 " + list.size() + " 个任务：\n");
                for (TaskDto t : list) {
                    sb.append("- ").append(t.id()).append(" | ").append(t.title())
                            .append(" | ").append(t.status()).append(" | ").append(t.progress()).append("%\n");
                }
                yield sb.toString();
            }
            case "get_task" -> {
                TaskDto t = tasks.status(require(args, "id"));
                yield t.title() + " — 状态 " + t.status() + "，进度 " + t.progress() + "%"
                        + (t.error() != null ? "，错误：" + t.error() : "");
            }
            case "send_task_message" -> {
                tasks.sendMessage(require(args, "id"), str(args, "content"));
                yield "已发送，任务将继续执行。";
            }
            case "stop_task" -> {
                tasks.stop(require(args, "id"));
                yield "已请求停止。";
            }
            case "delete_task" -> {
                tasks.delete(require(args, "id"));
                yield "已删除。";
            }
            default -> throw new ToolException("Unknown tool: " + name);
        };
        return textContent(text, false);
    }

    /**
     * Automation tools. The agent turns the user's words into a cron / time; the server validates
     * it and every reply spells the schedule back with concrete next run times, so the agent can
     * confirm with the user what will actually happen.
     */
    private void automationTools(ArrayNode tools) {
        ObjectNode scheduleFields = props(
                opt("type", "string", "recurring（周期，默认）/ once（指定时间执行一次）/ api（由外部系统调用触发）"),
                opt("cron", "string", "type=recurring 时必填。5 段 cron：分 时 日 月 周，按 timezone 解释。"
                        + "例：每小时整点 \"0 * * * *\"；每天 9:00 \"0 9 * * *\"；工作日 9:30 \"30 9 * * 1-5\"；"
                        + "每周一 10:00 \"0 10 * * 1\"；每月 1 号 8:00 \"0 8 1 * *\""),
                opt("run_at", "string", "type=once 时必填。本地时间，格式 2026-11-05T15:00，按 timezone 解释，须晚于当前时间。"
                        + "「明天」「下周一」这类相对时间请先调用 get_current_time 再换算"),
                opt("timezone", "string", "IANA 时区，默认 Asia/Shanghai"),
                opt("model", "string", "可选，执行用的模型 id；不填则用系统默认模型"),
                opt("overlap", "string", "上一次还没跑完时：skip（跳过，默认）/ parallel（并行再跑一次）"));
        ObjectNode create = props(req("prompt", "string", "每次执行时交给 Agent 的完整指令：要做什么、产出什么、存到哪里"),
                opt("name", "string", "简短名称；不填则取 prompt 开头"));
        create.setAll(scheduleFields.deepCopy());
        tools.add(tool("create_automation", "创建一个自动化：按周期（cron）、指定时间一次、或 API 调用自动执行 prompt，"
                + "每次执行会生成一个任务（结果在任务列表里）。创建前把要执行的内容和时间跟用户确认清楚；"
                + "创建后把返回的「接下来运行」时间告诉用户。", create, "prompt"));
        tools.add(tool("list_automations", "列出当前用户的自动化及其状态、下次运行时间。", props(), (String[]) null));
        tools.add(tool("get_automation", "查看一个自动化的详情、即将运行的时间和最近的运行记录。",
                props(req("id", "string", "自动化 id")), "id"));
        ObjectNode update = props(req("id", "string", "自动化 id"), opt("prompt", "string", "新的指令"),
                opt("name", "string", "新的名称"));
        update.setAll(scheduleFields.deepCopy());
        tools.add(tool("update_automation", "修改一个自动化，只需传要改的字段。", update, "id"));
        tools.add(tool("set_automation_enabled", "暂停（enabled=false）或恢复（enabled=true）一个自动化。",
                props(req("id", "string", "自动化 id"), req("enabled", "boolean", "true 恢复，false 暂停")),
                "id", "enabled"));
        tools.add(tool("run_automation_now", "立即执行一次自动化（不影响原定计划）。",
                props(req("id", "string", "自动化 id")), "id"));
        tools.add(tool("delete_automation", "删除一个自动化（已产生的任务保留）。",
                props(req("id", "string", "自动化 id")), "id"));
        tools.add(tool("get_current_time", "返回当前时间，用于把「明天 9 点」这类相对时间换算成具体时间。",
                props(opt("timezone", "string", "IANA 时区，默认 Asia/Shanghai")), (String[]) null));
    }

    /** Chat acts as its user, never as an admin: it can only change that user's own automations. */
    private String callAutomationTool(McpGrants.Grant grant, String name, JsonNode args) {
        String user = grant.owner();
        return switch (name) {
            case "create_automation" -> {
                AutomationDto a = automations.create(saveRequest(args), user, "chat");
                yield "已创建自动化 " + a.id() + "\n" + describe(automations.detail(a.id()));
            }
            case "list_automations" -> {
                List<AutomationDto> mine = automations.list().stream()
                        .filter(a -> Objects.equals(a.owner(), user)).toList();
                if (mine.isEmpty()) {
                    yield "当前没有自动化。";
                }
                StringBuilder sb = new StringBuilder("共 " + mine.size() + " 个自动化：\n");
                for (AutomationDto a : mine) {
                    sb.append("- ").append(a.id()).append(" | ").append(a.name()).append(" | ").append(schedule(a))
                            .append(" | ").append(a.enabled() ? "启用" : "已暂停")
                            .append(a.nextRunAt() != null ? " | 下次 " + when(a.nextRunAt(), a.timezone()) : "")
                            .append(a.lastStatus() != null ? " | 上次结果 " + a.lastStatus() : "").append("\n");
                }
                yield sb.toString();
            }
            case "get_automation" -> describe(automations.detail(require(args, "id")));
            case "update_automation" -> {
                AutomationDto a = automations.update(require(args, "id"), saveRequest(args), user, false);
                yield "已更新。\n" + describe(automations.detail(a.id()));
            }
            case "set_automation_enabled" -> {
                if (!args.has("enabled")) {
                    throw new ToolException("缺少参数：enabled");
                }
                AutomationDto a = automations.setEnabled(require(args, "id"), args.path("enabled").asBoolean(),
                        user, false);
                yield (a.enabled() ? "已恢复。" : "已暂停。") + "\n" + describe(automations.detail(a.id()));
            }
            case "run_automation_now" -> {
                AutomationRunDto run = automations.runNow(require(args, "id"), user, false);
                yield run.taskId() != null ? "已开始执行，任务 " + run.taskId() + "。可用 get_task 查看进度。"
                        : "未执行：" + run.skippedReason();
            }
            case "delete_automation" -> {
                automations.delete(require(args, "id"), user, false);
                yield "已删除（之前产生的任务保留）。";
            }
            case "get_current_time" -> {
                String tz = strOrNull(args, "timezone");
                yield "当前时间：" + when(Instant.now(), tz) + " " + AutomationSchedule.zone(tz).getId();
            }
            default -> throw new ToolException("Unknown tool: " + name);
        };
    }

    /** Fields the agent omitted stay null, so an update only changes what it named. */
    private static SaveAutomationRequest saveRequest(JsonNode args) {
        return new SaveAutomationRequest(strOrNull(args, "name"), strOrNull(args, "prompt"), strOrNull(args, "type"),
                strOrNull(args, "cron"), strOrNull(args, "run_at"), strOrNull(args, "timezone"),
                strOrNull(args, "model"), strOrNull(args, "overlap"), null);
    }

    private static String describe(AutomationDetailDto d) {
        AutomationDto a = d.automation();
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(a.name()).append("\n");
        sb.append("计划：").append(schedule(a)).append("（").append(a.timezone()).append("）\n");
        sb.append("状态：").append(a.enabled() ? "启用" : "已暂停").append("\n");
        sb.append("模型：").append(a.model() != null ? a.model() : "系统默认").append("\n");
        if (!d.upcoming().isEmpty()) {
            sb.append("接下来运行：").append(String.join("；",
                    d.upcoming().stream().map(t -> when(t, a.timezone())).toList())).append("\n");
        }
        if ("api".equals(a.type())) {
            sb.append("API 触发：POST ").append(a.triggerPath())
                    .append("（令牌请在「自动化」页面查看或重新生成，不会在对话中显示）\n");
        }
        if (!d.runs().isEmpty()) {
            sb.append("最近运行：\n");
            d.runs().stream().limit(5).forEach(r -> sb.append("- ").append(when(r.firedAt(), a.timezone()))
                    .append(" ").append(r.trigger()).append(" → ")
                    .append(r.taskId() != null ? "任务 " + r.taskId() + "（" + r.status() + "）"
                            : "跳过：" + r.skippedReason())
                    .append("\n"));
        }
        sb.append("Prompt：").append(a.prompt());
        return sb.toString();
    }

    private static String schedule(AutomationDto a) {
        return switch (a.type()) {
            case "recurring" -> "周期 cron \"" + a.cron() + "\"";
            case "once" -> "一次性 " + (a.runAt() != null ? when(a.runAt(), a.timezone()) : "");
            default -> "API 触发";
        };
    }

    private static String when(Instant t, String timezone) {
        return WHEN.format(t.atZone(AutomationSchedule.zone(timezone)));
    }

    // --- helpers ---

    private ObjectNode tool(String name, String description, ObjectNode properties, String... required) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode req = schema.putArray("required");
        if (required != null) {
            for (String r : required) {
                req.add(r);
            }
        }
        return t;
    }

    private ObjectNode props(ObjectNode... entries) {
        ObjectNode p = mapper.createObjectNode();
        for (ObjectNode e : entries) {
            p.set(e.get("__name").asText(), prop(e));
        }
        return p;
    }

    private ObjectNode req(String name, String type, String desc) { return field(name, type, desc); }
    private ObjectNode opt(String name, String type, String desc) { return field(name, type, desc); }

    private ObjectNode field(String name, String type, String desc) {
        ObjectNode e = mapper.createObjectNode();
        e.put("__name", name);
        e.put("type", type);
        e.put("description", desc);
        return e;
    }

    private ObjectNode prop(ObjectNode field) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", field.get("type").asText());
        p.put("description", field.get("description").asText());
        return p;
    }

    private ObjectNode textContent(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private ObjectNode toolError(String message) {
        return textContent("错误：" + message, true);
    }

    private ResponseEntity<JsonNode> ok(JsonNode id, JsonNode result) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("result", result);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<JsonNode> error(JsonNode id, int code, String message) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return ResponseEntity.ok(resp);
    }

    private static String str(JsonNode args, String key) {
        return args.path(key).asText("");
    }

    private static String strOrNull(JsonNode args, String key) {
        String v = args.path(key).asText("");
        return v.isBlank() ? null : v;
    }

    private static String require(JsonNode args, String key) {
        String v = args.path(key).asText("");
        if (v.isBlank()) {
            throw new ToolException("缺少参数：" + key);
        }
        return v;
    }

    private static final class ToolException extends RuntimeException {
        ToolException(String message) {
            super(message);
        }
    }
}
