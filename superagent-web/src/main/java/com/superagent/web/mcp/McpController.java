package com.superagent.web.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDto;
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

import java.util.List;

/**
 * SuperAgent's own MCP server, so a chat agent can operate tasks as tools
 * (create / list / get / steer / stop / delete). It speaks the MCP Streamable-HTTP
 * transport by hand: JSON-RPC 2.0 over a single {@code POST /mcp}, each request
 * answered with one {@code application/json} response (no server-initiated stream,
 * so {@code GET /mcp} is 405). Tool calls dispatch into {@link TaskService}.
 *
 * <p>Served on localhost for the local agent subprocess; it has no auth yet, like
 * the rest of the platform (addressed by the upcoming auth work).
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final TaskService tasks;
    private final McpToken token;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpController(TaskService tasks, McpToken token) {
        this.tasks = tasks;
        this.token = token;
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build(); // no server-initiated SSE
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> rpc(@RequestBody JsonNode body,
                                        @RequestHeader(value = "X-MCP-Token", required = false) String presented) {
        if (!token.value().equals(presented)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
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
                case "tools/list" -> ok(id, toolsListResult());
                case "tools/call" -> ok(id, callTool(body.path("params")));
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
                        opt("repo", "string", "可选代码库"), opt("branch", "string", "可选分支")),
                "title", "brief"));
        tools.add(tool("list_tasks", "列出所有任务及其状态。", props(), (String[]) null));
        tools.add(tool("get_task", "查看某个任务的状态与进度。",
                props(req("id", "string", "任务 id")), "id"));
        tools.add(tool("send_task_message", "给任务发送消息：回答确认、或变更/指挥其执行（任务非运行中时）。",
                props(req("id", "string", "任务 id"), req("content", "string", "要发送的内容")), "id", "content"));
        tools.add(tool("stop_task", "停止正在运行的任务。", props(req("id", "string", "任务 id")), "id"));
        tools.add(tool("delete_task", "删除一个任务。", props(req("id", "string", "任务 id")), "id"));
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        String text = switch (name) {
            case "create_task" -> {
                TaskDto t = tasks.create(new CreateTaskRequest(
                        str(args, "title"), str(args, "brief"), "web_chat",
                        strOrNull(args, "repo"), strOrNull(args, "branch"), null, null, null));
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
