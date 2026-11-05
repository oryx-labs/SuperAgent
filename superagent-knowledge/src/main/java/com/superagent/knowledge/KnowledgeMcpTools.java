package com.superagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.knowledge.KnowledgeEntryDto;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.str;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/** The run's user's knowledge base: list and create folders, write text, save a workspace file. */
@Component
public class KnowledgeMcpTools implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeMcpTools.class);

    private final KnowledgeService knowledge;
    private final McpGrants grants;

    public KnowledgeMcpTools(KnowledgeService knowledge, McpGrants grants) {
        this.knowledge = knowledge;
        this.grants = grants;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("knowledge_list_folders", "列出知识库里已有的全部目录（\"/\" 为根目录）。保存前先看看有没有合适的目录。",
                        props(), (String[]) null),
                tool("knowledge_create_folder", "在知识库中新建目录（可多级，如 研发规范/需求），已存在则直接返回。",
                        props(req("path", "string", "目录路径，用 / 分隔")), "path"),
                tool("knowledge_write", "把一段内容（如整理好的笔记、文档的 Markdown）直接写成知识库里的文件。目录不存在会自动创建；同名文件默认自动加序号，不覆盖。",
                        props(req("folder", "string", "知识库目标目录，如 笔记/会议；根目录用 \"\""),
                                req("name", "string", "文件名，含扩展名，如 周会纪要.md"),
                                req("content", "string", "文件内容（文本）"),
                                opt("overwrite", "boolean", "仅当用户明确要求覆盖同名文件时设为 true")),
                        "folder", "name", "content"),
                tool("knowledge_save", "把工作区里的一个文件保存到知识库。目录不存在会自动创建；同名文件默认自动加序号，不覆盖。",
                        props(req("file", "string", "工作区内的文件路径（相对工作区，如 output.md）"),
                                req("folder", "string", "知识库目标目录，如 研发规范/需求；根目录用 \"\""),
                                opt("name", "string", "保存后的文件名，默认沿用原文件名"),
                                opt("overwrite", "boolean", "仅当用户明确要求覆盖同名文件时设为 true")),
                        "file", "folder"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String owner = requireOwner(grant, "知识库");
        try {
            String text = switch (name) {
                case "knowledge_list_folders" -> {
                    List<String> folders = knowledge.folders(owner);
                    yield folders.size() == 1 ? "知识库还没有任何目录（只有根目录 /）。"
                            : "知识库目录：\n" + String.join("\n", folders.stream().map(f -> "/" + f).toList());
                }
                case "knowledge_create_folder" ->
                        "目录已就绪：/" + knowledge.ensureFolder(owner, folder(require(args, "path"))).path();
                case "knowledge_write" -> {
                    KnowledgeEntryDto saved = knowledge.writeText(owner, folder(str(args, "folder")),
                            require(args, "name"), args.path("content").asText(""), args.path("overwrite").asBoolean(false));
                    log.info("{} {} wrote {} to knowledge base of {}", grant.kind(), grant.taskId(), saved.path(), owner);
                    yield "已保存到知识库：/" + saved.path();
                }
                case "knowledge_save" -> {
                    KnowledgeEntryDto saved = knowledge.saveFile(owner, grants.workspaceFile(grant, require(args, "file")),
                            folder(str(args, "folder")), strOrNull(args, "name"), args.path("overwrite").asBoolean(false));
                    log.info("{} {} saved {} to knowledge base of {}", grant.kind(), grant.taskId(), saved.path(), owner);
                    yield "已保存到知识库：/" + saved.path();
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            };
            return McpToolResult.ok(text);
        } catch (IllegalArgumentException | KnowledgeNotFoundException | KnowledgeConflictException e) {
            throw new McpToolException(e.getMessage());
        }
    }

    /** Agents tend to write folder paths with a leading or trailing "/". */
    private static String folder(String path) {
        return path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
    }
}
