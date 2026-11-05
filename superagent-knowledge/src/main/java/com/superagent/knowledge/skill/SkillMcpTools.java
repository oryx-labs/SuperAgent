package com.superagent.knowledge.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.skill.SkillDto;
import com.superagent.api.skill.SkillRequest;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.str;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/** Maintaining shared skills by conversation: list, read, create, update, add an image from the workspace. */
@Component
public class SkillMcpTools implements McpToolProvider {

    private final SkillService skills;
    private final McpGrants grants;

    public SkillMcpTools(SkillService skills, McpGrants grants) {
        this.skills = skills;
        this.grants = grants;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("skill_list", "列出所有共享 skill（工作规范）：id、名称、分类、适用场景。", props(), (String[]) null),
                tool("skill_get", "查看一个 skill 的完整内容和图片列表。", props(req("id", "string", "skill id")), "id"),
                tool("skill_create", "新建一个共享 skill。description 要写清楚什么时候使用它，Agent 靠它判断是否适用。",
                        props(req("name", "string", "名称，如 写作规范"), req("description", "string", "适用场景，一句话"),
                                opt("category", "string", "分类，如 写作 / 设计 / 运营"),
                                req("content", "string", "正文（Markdown）：规范、步骤、示例；图片用 images/文件名 引用")),
                        "name", "description", "content"),
                tool("skill_update", "修改一个 skill，只传要改的字段。修改会影响所有用这个 skill 的 Agent，仅在用户要求时使用。",
                        props(req("id", "string", "skill id"), opt("name", "string", "新名称"),
                                opt("description", "string", "新的适用场景"), opt("category", "string", "新分类"),
                                opt("content", "string", "新的完整正文（Markdown），会整体替换原正文"),
                                opt("enabled", "boolean", "是否启用")),
                        "id"),
                tool("skill_add_image", "把工作区里的一张图片（PNG/JPEG/GIF/WebP）加到 skill 中，返回可在正文里引用的路径。",
                        props(req("id", "string", "skill id"), req("file", "string", "工作区内的图片路径"),
                                opt("name", "string", "图片名，默认沿用文件名")),
                        "id", "file"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
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
                default -> throw new McpToolException("Unknown tool: " + name);
            };
            return McpToolResult.ok(text);
        } catch (IllegalArgumentException | SkillNotFoundException e) {
            throw new McpToolException(e.getMessage());
        } catch (IOException e) {
            throw new McpToolException("Cannot read the image: " + e.getMessage());
        }
    }
}
