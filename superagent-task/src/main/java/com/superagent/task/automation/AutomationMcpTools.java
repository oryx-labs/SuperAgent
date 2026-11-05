package com.superagent.task.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.automation.AutomationDetailDto;
import com.superagent.api.automation.AutomationDto;
import com.superagent.api.automation.AutomationRunDto;
import com.superagent.api.automation.SaveAutomationRequest;
import com.superagent.core.automation.AutomationSchedule;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * Automation tools for chat. The agent turns the user's words into a cron / time; the server validates
 * it and every reply spells the schedule back with concrete next run times, so the agent can confirm
 * with the user what will actually happen.
 */
@Component
public class AutomationMcpTools implements McpToolProvider {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)");

    private final AutomationService automations;

    public AutomationMcpTools(AutomationService automations) {
        this.automations = automations;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT);
    }

    @Override
    public List<ObjectNode> tools() {
        List<ObjectNode> tools = new ArrayList<>();
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
        return tools;
    }

    /** Chat acts as its user, never as an admin: it can only change that user's own automations. */
    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        try {
            return McpToolResult.ok(run(grant.owner(), name, args));
        } catch (IllegalArgumentException | AutomationNotFoundException | AutomationForbiddenException e) {
            throw new McpToolException(e.getMessage()); // e.g. a bad cron: the agent can fix it and retry
        }
    }

    private String run(String user, String name, JsonNode args) {
        return switch (name) {
            case "create_automation" -> {
                AutomationDto a = automations.create(saveRequest(args), user, "chat");
                yield "已创建自动化 " + a.id() + "\n" + describe(automations.detail(a.id(), user, false));
            }
            case "list_automations" -> {
                List<AutomationDto> mine = automations.list(user, false);
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
            case "get_automation" -> describe(automations.detail(require(args, "id"), user, false));
            case "update_automation" -> {
                AutomationDto a = automations.update(require(args, "id"), saveRequest(args), user, false);
                yield "已更新。\n" + describe(automations.detail(a.id(), user, false));
            }
            case "set_automation_enabled" -> {
                if (!args.has("enabled")) {
                    throw new McpToolException("缺少参数：enabled");
                }
                AutomationDto a = automations.setEnabled(require(args, "id"), args.path("enabled").asBoolean(),
                        user, false);
                yield (a.enabled() ? "已恢复。" : "已暂停。") + "\n" + describe(automations.detail(a.id(), user, false));
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
            default -> throw new McpToolException("Unknown tool: " + name);
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
}
