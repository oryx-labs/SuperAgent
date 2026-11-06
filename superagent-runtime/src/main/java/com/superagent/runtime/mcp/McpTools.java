package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builders for tool definitions and readers for tool arguments, shared by every {@link McpToolProvider}. */
public final class McpTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpTools() {
    }

    public static ObjectNode tool(String name, String description, ObjectNode properties, String... required) {
        ObjectNode t = JSON.createObjectNode();
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

    /** A properties object from fields built with {@link #req} / {@link #opt}. */
    public static ObjectNode props(ObjectNode... fields) {
        ObjectNode p = JSON.createObjectNode();
        for (ObjectNode f : fields) {
            ObjectNode prop = f.deepCopy(); // type, description and anything else set on it (e.g. items)
            prop.remove("__name");
            p.set(f.get("__name").asText(), prop);
        }
        return p;
    }

    /** A field listed in the tool's {@code required}; the distinction is only in the name list passed to {@link #tool}. */
    public static ObjectNode req(String name, String type, String desc) {
        return field(name, type, desc);
    }

    public static ObjectNode opt(String name, String type, String desc) {
        return field(name, type, desc);
    }

    private static ObjectNode field(String name, String type, String desc) {
        ObjectNode e = JSON.createObjectNode();
        e.put("__name", name);
        e.put("type", type);
        e.put("description", desc);
        return e;
    }

    public static String str(JsonNode args, String key) {
        return args.path(key).asText("");
    }

    public static String strOrNull(JsonNode args, String key) {
        String v = args.path(key).asText("");
        return v.isBlank() ? null : v;
    }

    public static String require(JsonNode args, String key) {
        String v = args.path(key).asText("");
        if (v.isBlank()) {
            throw new McpToolException("缺少参数：" + key);
        }
        return v;
    }

    /** Runs need a signed-in owner for anything personal (files, inbox). */
    public static String requireOwner(McpGrants.Grant grant, String what) {
        if (grant.owner() == null || grant.owner().isBlank()) {
            throw new McpToolException(what + "需要登录用户，本次运行没有关联用户。");
        }
        return grant.owner();
    }
}
