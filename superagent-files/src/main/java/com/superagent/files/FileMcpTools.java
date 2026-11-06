package com.superagent.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.file.WorkFile;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * The agent's way to hand over work results: write a text file, save a file from its workspace (images,
 * videos, slides…), and list what it made before so it can revise one. Files land in the user's 文件, not
 * the knowledge base.
 */
@Component
public class FileMcpTools implements McpToolProvider {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final FileService files;
    private final McpGrants grants;

    public FileMcpTools(FileService files, McpGrants grants) {
        this.files = files;
        this.grants = grants;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        String replace = "要修改之前做过的文件时填它的 id（用 file_list 查），会覆盖为新内容；不填则新建一个文件";
        return List.of(
                tool("file_write", "把工作成果（文章、报告、方案等，通常是 Markdown）写成一个文件交给用户。用户会在「文件」里看到它，对话里也会出现文件卡片。",
                        props(req("name", "string", "文件名，含扩展名，如 第01节讲义.md"),
                                req("content", "string", "文件内容（文本）"),
                                opt("replace_id", "string", replace)),
                        "name", "content"),
                tool("file_save", "把工作区里的一个文件（如生成的图片、视频、PPT、PDF）作为工作成果交给用户。",
                        props(req("file", "string", "工作区内的文件路径（相对工作区，如 out/封面.png）"),
                                opt("name", "string", "交付时的文件名，默认沿用原文件名"),
                                opt("replace_id", "string", replace)),
                        "file"),
                tool("file_list", "列出你之前交付过的文件和用户发给你的文件（最新的在前），用于找回资料或找到要修改的那一个。", props(), (String[]) null));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String owner = requireOwner(grant, "文件");
        FileService.Origin origin = grant.kind() == McpGrants.Kind.TASK
                ? files.forTask(owner, grant.taskId())
                : new FileService.Origin(owner, grant.agentId(), null, grant.sessionId(), grant.messageId(),
                WorkFile.ORIGIN_CHAT);
        try {
            String text = switch (name) {
                case "file_write" -> saved(files.writeText(origin, require(args, "name"),
                        args.path("content").asText(""), strOrNull(args, "replace_id")));
                case "file_save" -> saved(files.saveFrom(origin, grants.workspaceFile(grant, require(args, "file")),
                        strOrNull(args, "name"), strOrNull(args, "replace_id")));
                case "file_list" -> {
                    List<WorkFile> list = files.producedBy(origin).stream().limit(50).toList();
                    if (list.isEmpty()) {
                        yield "还没有文件。";
                    }
                    StringBuilder sb = new StringBuilder("共 " + list.size() + " 个文件：\n");
                    list.forEach(f -> sb.append("- ").append(f.id()).append(" | ").append(f.name())
                            .append(WorkFile.ORIGIN_UPLOAD.equals(f.origin()) ? "（用户上传，只读）" : "")
                            .append(" | 更新于 ").append(WHEN.format(f.updatedAt())).append("\n"));
                    yield sb.toString();
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            };
            return McpToolResult.ok(text);
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
    }

    /** The UI recognises this line to show a file card while the reply is still streaming. */
    private static String saved(WorkFile f) {
        return "已保存文件「" + f.name() + "」（id: " + f.id() + "）。用户可在「文件」中查看，回复里提一下文件名即可，不必贴路径。";
    }
}
