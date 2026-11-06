package com.superagent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.agent.AgentNotFoundException;
import com.superagent.api.skill.SkillDto;
import com.superagent.api.superagent.SuperAgentPlanDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.SuperAgentWork;
import com.superagent.core.task.Task;
import com.superagent.skills.SkillService;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import com.superagent.task.TaskBusyException;
import com.superagent.task.TaskNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The tools a SuperAgent round can call over SuperAgent's MCP endpoint, always acting as the
 * round's owner. Hiring, re-briefing and assigning are refused by {@link SuperAgentService} until
 * the user approves a plan; a refusal comes back as a tool error the agent can read and act on.
 */
@Component
public class SuperAgentTools implements McpToolProvider {

    /** Every tool name, in the order they're listed; the round's CLI allowlist is built from this. */
    static final List<String> NAMES = List.of("list_team", "list_skills", "propose_plan", "hire_member",
            "update_member", "assign_task", "list_assigned_tasks", "get_task", "reply_task", "get_current_time",
            SuperAgentTools.TELL_USER);

    /** Background rounds only: posts into the chat. */
    static final String TELL_USER = "tell_user";

    /** A tool's outcome; {@code error} results are shown to the agent as tool errors. */
    public record Result(String text, boolean error) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)");

    private final SuperAgentService service;
    private final SkillService skills;
    private final ObjectMapper mapper = new ObjectMapper();

    public SuperAgentTools(SuperAgentService service, SkillService skills) {
        this.service = service;
        this.skills = skills;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.SUPER);
    }

    @Override
    public List<ObjectNode> tools() {
        List<ObjectNode> out = new ArrayList<>();
        list().forEach(t -> out.add((ObjectNode) t));
        return out;
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        Result r = run(grant, name, args);
        return new McpToolResult(r.text(), r.error());
    }

    public ArrayNode list() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("list_team", "列出你的团队成员（用户的全部 Agent，不含你自己）：id、名称、人设、模型、技能。派活前先看能否复用。",
                props()));
        tools.add(tool("list_skills", "列出可以绑定给成员的技能（id、名称、说明）。", props()));

        ObjectNode member = object(
                field("agent_id", "string", "复用已有成员时填其 id；新成员不填"),
                field("name", "string", "成员名称，如「市场调研员」"),
                field("specialty", "string", "此人在本计划中的专长与职责"));
        ObjectNode task = object(
                field("title", "string", "任务标题"),
                field("assignee", "string", "负责成员的名称（与 members 中的 name 一致）"),
                field("brief", "string", "任务说明：目标、范围、交付物与验收标准"));
        tools.add(tool("propose_plan", "提交一个计划给用户确认（会替换之前未执行的计划）。用户在界面上点击「确认执行」后，"
                        + "你才能招人、调整成员和派任务。",
                props(field("summary", "string", "你对用户目标的理解，以及整体做法（几句话）"),
                        array("members", "参与的成员", member), array("tasks", "拆分出的任务及负责人", task)),
                "summary", "members", "tasks"));
        tools.add(tool("hire_member", "（计划确认后）新增一名团队成员。",
                props(field("name", "string", "成员名称"),
                        field("persona", "string", "人设：角色定位、擅长什么、工作方式、交付标准"),
                        field("model", "string", "可选，模型 id；不填用系统默认"),
                        stringArray("skill_ids", "可选，要绑定的技能 id")),
                "name", "persona"));
        tools.add(tool("update_member", "（计划确认后）调整一名已有成员，只传要改的字段。",
                props(field("id", "string", "成员 id"), field("name", "string", "新名称"),
                        field("persona", "string", "新人设"), field("model", "string", "新模型 id"),
                        stringArray("skill_ids", "新的技能 id 列表（整体替换）")),
                "id"));
        tools.add(tool("assign_task", "（计划确认后）把一个任务派给某个成员，立即开始执行。完成、失败或需要确认时你会收到系统消息。",
                props(field("member_id", "string", "成员 id"), field("title", "string", "任务标题"),
                        field("brief", "string", "任务说明：目标、范围、交付物与验收标准")),
                "member_id", "title", "brief"));
        tools.add(tool("list_assigned_tasks", "列出你派出的、仍在跟进的任务及其状态。", props()));
        tools.add(tool("get_task", "查看一个任务的状态与进度。", props(field("id", "string", "任务 id")), "id"));
        tools.add(tool("reply_task", "回复一个正在等待确认的任务（你能自己判断的小问题）。",
                props(field("task_id", "string", "任务 id"), field("content", "string", "回复内容")),
                "task_id", "content"));
        tools.add(tool("get_current_time", "返回当前时间。", props()));
        tools.add(tool(TELL_USER, "（仅后台处理时可用）把用户需要知道的事发到你和用户的聊天里：任务完成或失败的汇报、需要用户拍板的事、紧急的事。"
                        + "每条写清楚是什么事、结果或建议，用户不用再追问就能明白。不重要的事不要发。",
                props(field("text", "string", "发给用户的话（Markdown）")), "text"));
        return tools;
    }

    public Result run(McpGrants.Grant grant, String name, JsonNode args) {
        String owner = grant.owner();
        try {
            if (SuperAgentService.ACTING_TOOLS.contains(name)) {
                service.requireOwnInput(grant);
            }
            return new Result(switch (name) {
                case "list_team" -> {
                    List<SuperAgentService.TeamMember> team = service.team(owner);
                    if (team.isEmpty()) {
                        yield "团队还没有成员。";
                    }
                    StringBuilder sb = new StringBuilder("团队成员（" + team.size() + " 人）：\n");
                    for (SuperAgentService.TeamMember m : team) {
                        sb.append("- ").append(m.id()).append(" | ").append(m.name())
                                .append(m.hired() ? "（你招的）" : "（用户自建）")
                                .append(" | 模型 ").append(m.model() != null ? m.model() : "系统默认")
                                .append(m.skillIds().isEmpty() ? "" : " | 技能 " + String.join(",", m.skillIds()))
                                .append("\n  人设：").append(m.persona() != null ? oneLine(m.persona()) : "（未设置）")
                                .append("\n");
                    }
                    yield sb.toString();
                }
                case "list_skills" -> {
                    List<SkillDto> list = skills.list(owner).stream().filter(SkillDto::enabled).toList();
                    if (list.isEmpty()) {
                        yield "暂无可用技能。";
                    }
                    StringBuilder sb = new StringBuilder();
                    list.forEach(s -> sb.append("- ").append(s.id()).append(" | ").append(s.name())
                            .append(s.description() != null ? " | " + oneLine(s.description()) : "").append("\n"));
                    yield sb.toString();
                }
                case "propose_plan" -> {
                    SuperAgentPlanDto plan = service.proposePlan(owner, text(args, "summary"),
                            members(args.path("members")), assignments(args.path("tasks")));
                    yield "计划 #" + plan.id() + " 已提交，等待用户在界面上点击「确认执行」。"
                            + "请在回复里简要说明这个计划。确认之前不要调用招人、调整或派任务的工具。";
                }
                case "hire_member" -> {
                    Agent a = service.hire(owner, text(args, "name"), text(args, "persona"), null,
                            text(args, "model"), strings(args.path("skill_ids")));
                    yield "已招入成员 " + a.name() + "（id " + a.id() + "）。";
                }
                case "update_member" -> {
                    Agent a = service.adjust(owner, required(args, "id"), text(args, "name"), text(args, "persona"),
                            text(args, "model"), args.has("skill_ids") ? strings(args.path("skill_ids")) : null);
                    yield "已更新成员 " + a.name() + "。";
                }
                case "assign_task" -> {
                    String taskId = service.assign(owner, required(args, "member_id"), text(args, "title"),
                            text(args, "brief"));
                    yield "已派出任务 " + taskId + "，正在执行。结束后你会收到系统消息。";
                }
                case "list_assigned_tasks" -> {
                    List<SuperAgentWork> tracked = service.assigned(owner);
                    if (tracked.isEmpty()) {
                        yield "目前没有在跟进的任务。";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (SuperAgentWork t : tracked) {
                        String status = service.task(owner, t.taskId()).map(x -> x.status().name()).orElse("DELETED");
                        sb.append("- ").append(t.taskId()).append(" | ").append(t.title()).append(" | ")
                                .append(t.memberName()).append(" | ").append(status).append("\n");
                    }
                    yield sb.toString();
                }
                case "get_task" -> {
                    Task t = service.task(owner, required(args, "id"))
                            .orElseThrow(() -> new IllegalArgumentException("No such task"));
                    yield t.title() + " — 状态 " + t.status() + "，进度 " + t.progress() + "%"
                            + (t.error() != null ? "，错误：" + t.error() : "");
                }
                case "reply_task" -> {
                    service.replyToTask(owner, required(args, "task_id"), required(args, "content"));
                    yield "已回复，任务将继续执行。";
                }
                case TELL_USER -> {
                    service.tellUser(grant, required(args, "text"));
                    yield "已发到聊天。";
                }
                case "get_current_time" -> "当前时间：" + WHEN.format(Instant.now().atZone(ZoneId.of("Asia/Shanghai")))
                        + " Asia/Shanghai";
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            }, false);
        } catch (IllegalArgumentException | AgentNotFoundException | TaskNotFoundException | TaskBusyException e) {
            return new Result("错误：" + e.getMessage(), true);
        }
    }

    // --- argument parsing ---

    private static List<SuperAgentPlanDto.Member> members(JsonNode array) {
        List<SuperAgentPlanDto.Member> out = new ArrayList<>();
        for (JsonNode m : array) {
            out.add(new SuperAgentPlanDto.Member(text(m, "agent_id"), required(m, "name"), text(m, "specialty")));
        }
        return out;
    }

    private static List<SuperAgentPlanDto.Assignment> assignments(JsonNode array) {
        List<SuperAgentPlanDto.Assignment> out = new ArrayList<>();
        for (JsonNode t : array) {
            out.add(new SuperAgentPlanDto.Assignment(required(t, "title"), text(t, "assignee"), text(t, "brief")));
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> {
            if (!v.asText("").isBlank()) {
                out.add(v.asText().strip());
            }
        });
        return out;
    }

    private static String text(JsonNode args, String key) {
        String v = args.path(key).asText("");
        return v.isBlank() ? null : v.strip();
    }

    private static String required(JsonNode args, String key) {
        String v = text(args, key);
        if (v == null) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return v;
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    // --- schema helpers ---

    private ObjectNode tool(String name, String description, ObjectNode properties, String... required) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode req = schema.putArray("required");
        for (String r : required) {
            req.add(r);
        }
        return t;
    }

    private ObjectNode props(ObjectNode... fields) {
        ObjectNode p = mapper.createObjectNode();
        for (ObjectNode f : fields) {
            String name = f.remove("__name").asText();
            p.set(name, f);
        }
        return p;
    }

    private ObjectNode field(String name, String type, String description) {
        ObjectNode f = mapper.createObjectNode();
        f.put("__name", name);
        f.put("type", type);
        f.put("description", description);
        return f;
    }

    private ObjectNode object(ObjectNode... fields) {
        ObjectNode o = mapper.createObjectNode();
        o.put("type", "object");
        o.set("properties", props(fields));
        return o;
    }

    private ObjectNode array(String name, String description, ObjectNode items) {
        ObjectNode a = mapper.createObjectNode();
        a.put("__name", name);
        a.put("type", "array");
        a.put("description", description);
        a.set("items", items);
        return a;
    }

    private ObjectNode stringArray(String name, String description) {
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        return array(name, description, items);
    }
}
