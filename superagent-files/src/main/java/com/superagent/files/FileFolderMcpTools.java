package com.superagent.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.file.WorkFileDto;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * Keeping the user's Files in order from a conversation: find any file they can see (not just this agent's), move
 * it, and manage their folders. Only ever the user's own tree, and only files they can already see. Not for a
 * SuperAgent background round: those read third-party messages, which mustn't rearrange anyone's files.
 */
@Component
public class FileFolderMcpTools implements McpToolProvider {

    private static final int FIND_LIMIT = 50;

    private final FileService files;
    private final FileFolderService folders;

    public FileFolderMcpTools(FileService files, FileFolderService folders) {
        this.files = files;
        this.folders = folders;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK, McpGrants.Kind.SUPER);
    }

    @Override
    public List<ObjectNode> tools() {
        String path = "目录路径，如「项目/Q4」";
        return List.of(
                tool("file_find", "在用户能看到的所有文件里查找（不限于你自己做的）。返回 id、名称、所在目录、标签。",
                        props(opt("query", "string", "文件名、标签或来源 Agent 名的一部分；不填则列出最近的文件"),
                                opt("folder", "string", "只查这个目录及其子目录，" + path + "；填「未归档」查没放目录的文件")),
                        (String[]) null),
                tool("file_folders", "列出用户的目录树和每个目录里的文件数。", props(), (String[]) null),
                tool("file_move", "把用户能看到的一个文件移到他的某个目录（只影响他自己看到的位置，不影响别人）。",
                        props(req("file_id", "string", "文件 id（用 file_find 查）"),
                                opt("folder", "string", path + "；不填则移出目录，回到未归档")),
                        "file_id"),
                tool("file_create_folder", "新建目录。只有用户明确要求新建时才用，不要自己决定建目录。",
                        props(req("path", "string", path + "，上级不存在会一起建，最多 3 层")), "path"),
                tool("file_rename_folder", "重命名用户的一个目录。",
                        props(req("path", "string", path), req("name", "string", "新名字（只是名字，不含路径）")), "path", "name"),
                tool("file_move_folder", "把目录连同里面的内容移到另一个目录下（最多 3 层）。",
                        props(req("path", "string", "要移动的目录，" + path),
                                opt("parent", "string", "移到哪个目录下；不填则移到最上层")), "path"),
                tool("file_delete_folder", "删除目录。里面的文件和子目录退回上一级，不会删除任何文件。只有用户明确要求时才用。",
                        props(req("path", "string", path)), "path"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "目录");
        if (grant.kind() == McpGrants.Kind.SUPER && grant.sessionId() == null) {
            throw new McpToolException("整理文件只能在和用户的聊天里进行");
        }
        try {
            return McpToolResult.ok(switch (name) {
                case "file_find" -> find(user, strOrNull(args, "query"), strOrNull(args, "folder"));
                case "file_folders" -> tree(user);
                case "file_move" -> {
                    String folder = strOrNull(args, "folder");
                    folders.moveToPath(user, require(args, "file_id"), folder);
                    yield folder == null || folder.isBlank() ? "已移出目录，回到未归档。" : "已移到目录「" + folder.strip() + "」。";
                }
                case "file_create_folder" -> "已新建目录「" + folders.createPath(user, require(args, "path")) + "」。";
                case "file_rename_folder" -> "目录现在是「" + folders.renamePath(user, require(args, "path"), require(args, "name")) + "」。";
                case "file_move_folder" -> "目录现在是「" + folders.movePath(user, require(args, "path"), strOrNull(args, "parent")) + "」。";
                case "file_delete_folder" -> {
                    folders.deletePath(user, require(args, "path"));
                    yield "已删除目录，里面的文件和子目录退回上一级。";
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            });
        } catch (IllegalArgumentException | NotFoundException | ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }

    private String find(String user, String query, String folder) {
        Map<String, String> paths = folders.list(user).stream()
                .collect(Collectors.toMap(com.superagent.api.file.FileFolderDto::id, com.superagent.api.file.FileFolderDto::path));
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String within = folder == null ? "" : folder.strip();
        List<WorkFileDto> hits = files.list(user, null).stream()
                .filter(f -> q.isEmpty() || contains(f.name(), q) || contains(f.agentName(), q)
                        || f.tags().stream().anyMatch(t -> contains(t, q)))
                .filter(f -> {
                    String at = f.folderId() == null ? null : paths.get(f.folderId());
                    return within.isEmpty() || (within.equals("未归档") ? at == null
                            : at != null && (at.equals(within) || at.startsWith(within + "/")));
                }).toList();
        if (hits.isEmpty()) {
            return "没有找到文件。";
        }
        StringBuilder sb = new StringBuilder("共 " + hits.size() + " 个文件" + (hits.size() > FIND_LIMIT ? "，只列前 " + FIND_LIMIT + " 个" : "") + "：\n");
        hits.stream().limit(FIND_LIMIT).forEach(f -> sb.append("- ").append(f.id()).append(" | ").append(f.name())
                .append(" | 目录：").append(f.folderId() == null ? "未归档" : paths.getOrDefault(f.folderId(), "未归档"))
                .append(f.agentName() == null ? "" : " | 来自 " + f.agentName())
                .append(f.tags().isEmpty() ? "" : " | 标签：" + String.join("，", f.tags()))
                .append(f.canManage() ? "" : " | 只读").append("\n"));
        return sb.toString();
    }

    private static boolean contains(String text, String q) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q);
    }

    private String tree(String user) {
        var list = folders.list(user);
        if (list.isEmpty()) {
            return "用户还没有目录。";
        }
        Map<String, Long> counts = files.list(user, null).stream().filter(f -> f.folderId() != null)
                .collect(Collectors.groupingBy(WorkFileDto::folderId, Collectors.counting()));
        return list.stream().map(f -> "- " + f.path() + "（" + counts.getOrDefault(f.id(), 0L) + " 个文件）")
                .collect(Collectors.joining("\n"));
    }
}
