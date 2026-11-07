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
 * videos, slides…), and list what it made before so it can revise one. Files land in the user's 文件, in the
 * folder the agent names if any; otherwise the auto-filer sorts them.
 */
@Component
public class FileMcpTools implements McpToolProvider {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final FileService files;
    private final McpGrants grants;
    private final FileFolderService folders;

    public FileMcpTools(FileService files, McpGrants grants, FileFolderService folders) {
        this.files = files;
        this.grants = grants;
        this.folders = folders;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        String replace = "要修改之前做过的文件时填它的 id（用 file_list 查），会覆盖为新内容；不填则新建一个文件";
        String tags = "1~3 个主题标签，逗号分隔，如「课程,讲义」。按内容主题打（不要写文件类型或 Agent 名），"
                + "优先复用 file_list 里列出的已有标签，不要造同义词";
        String folder = "放到用户的哪个目录，写完整路径，如「项目/Q4」（只能用 file_list 列出的已有目录）。用户说了放哪就填，"
                + "没说就不填，系统会自动归档";
        return List.of(
                tool("file_write", "把工作成果（文章、报告、方案等，通常是 Markdown）写成一个文件交给用户。用户会在「文件」里看到它，对话里也会出现文件卡片。",
                        props(req("name", "string", "文件名，含扩展名，如 第01节讲义.md"),
                                req("content", "string", "文件内容（文本）"),
                                opt("replace_id", "string", replace),
                                opt("tags", "string", tags),
                                opt("folder", "string", folder)),
                        "name", "content"),
                tool("file_save", "把工作区里的一个文件（如生成的图片、视频、PPT、PDF）作为工作成果交给用户。",
                        props(req("file", "string", "工作区内的文件路径（相对工作区，如 out/封面.png）"),
                                opt("name", "string", "交付时的文件名，默认沿用原文件名"),
                                opt("replace_id", "string", replace),
                                opt("tags", "string", tags),
                                opt("folder", "string", folder)),
                        "file"),
                tool("file_get", "把 Files 里用户能看到的一个文件取到你的工作区，用来阅读或处理（PPT、PDF、图片等都可以），返回它在工作区的路径。",
                        props(req("file_id", "string", "文件 id（任务说明里给的，或用 file_find 查到的）")), "file_id"),
                tool("file_list", "列出你之前交付过的文件和用户发给你的文件（最新的在前），以及已在用的标签；用于找回资料、找到要修改的那一个、复用标签。",
                        props(), (String[]) null));
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
                case "file_write" -> placed(owner, files.writeText(origin, require(args, "name"),
                        args.path("content").asText(""), strOrNull(args, "replace_id"), tags(args)), strOrNull(args, "folder"));
                case "file_save" -> placed(owner, files.saveFrom(origin, grants.workspaceFile(grant, require(args, "file")),
                        strOrNull(args, "name"), strOrNull(args, "replace_id"), tags(args)), strOrNull(args, "folder"));
                case "file_get" -> {
                    if (grant.workspace() == null) {
                        throw new McpToolException("这次运行没有工作区");
                    }
                    yield "文件已取到工作区：" + files.copyToWorkspace(require(args, "file_id"), owner, grant.workspace()) + "（只是副本，改完要交付请用 file_write 或 file_save）。";
                }
                case "file_list" -> {
                    List<WorkFile> list = files.producedBy(origin).stream().limit(50).toList();
                    List<String> inUse = files.tagsInUse(owner, origin.agentId());
                    List<String> paths = folders.paths(owner);
                    String vocabulary = (inUse.isEmpty() ? "" : "\n已在用的标签：" + String.join("，", inUse.stream().limit(40).toList()))
                            + (paths.isEmpty() ? "" : "\n用户的目录：" + String.join("，", paths.stream().limit(100).toList()));
                    if (list.isEmpty()) {
                        yield "还没有文件。" + vocabulary;
                    }
                    StringBuilder sb = new StringBuilder("共 " + list.size() + " 个文件：\n");
                    list.forEach(f -> sb.append("- ").append(f.id()).append(" | ").append(f.name())
                            .append(WorkFile.LINK.equals(f.storage()) ? "（用户分享的链接：" + f.location() + "）"
                                    : WorkFile.ORIGIN_UPLOAD.equals(f.origin()) ? "（用户上传，只读）" : "")
                            .append(f.tags().isEmpty() ? "" : " | 标签：" + String.join("，", f.tags()))
                            .append(" | 更新于 ").append(WHEN.format(f.updatedAt())).append("\n"));
                    yield sb + vocabulary;
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            };
            return McpToolResult.ok(text);
        } catch (IllegalArgumentException | com.superagent.core.error.NotFoundException e) {
            throw new McpToolException(e.getMessage());
        }
    }

    /** The file is saved whatever happens with the folder: a wrong folder name mustn't cost the work. */
    private String placed(String owner, WorkFile f, String folder) {
        if (folder == null || folder.isBlank()) {
            return saved(f);
        }
        return saved(f) + (folders.placeByAgent(owner, f.id(), folder) ? "已放进目录「" + folder.strip() + "」。"
                : "没有目录「" + folder.strip() + "」，文件先留在未归档（用 file_list 查看已有目录）。");
    }

    /** Tags as the agent sends them: a comma-separated string, or (some models) a JSON array. */
    static List<String> tags(JsonNode args) {
        JsonNode t = args.path("tags");
        if (t.isArray()) {
            List<String> out = new java.util.ArrayList<>();
            t.forEach(x -> out.add(x.asText("")));
            return out;
        }
        return t.isTextual() ? java.util.Arrays.asList(t.asText().split("[,，、;；\n]")) : null;
    }

    /** The UI recognises this line to show a file card while the reply is still streaming. */
    private static String saved(WorkFile f) {
        return "已保存文件「" + f.name() + "」（id: " + f.id() + "）。用户可在「文件」中查看，回复里提一下文件名即可，不必贴路径。";
    }
}
