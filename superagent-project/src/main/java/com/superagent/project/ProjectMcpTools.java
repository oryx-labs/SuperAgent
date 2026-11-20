package com.superagent.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.project.ProjectRequests;
import com.superagent.core.project.ProjectMemory;
import com.superagent.core.project.ProjectRepo;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * The project's shared context for the agents in it: reading all of it (the prompt carries a capped copy), writing to
 * its memory, and keeping its brief and repositories when the person who set the run off owns the project. No
 * confirmation step: what an agent writes is recorded with where it came from. Outside a project all refuse.
 */
@Component
public class ProjectMcpTools implements McpToolProvider {

    private final ProjectService projects;

    public ProjectMcpTools(ProjectService projects) {
        this.projects = projects;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("project_context_read", "读你所在项目的完整共同上下文：项目说明、资料索引（项目目录里的文件和项目仓库）、项目记忆。"
                                + "提示词里只带了精简版，需要细节时调用；填 memory_id 只读那一条记忆的全文。",
                        props(opt("memory_id", "string", "只读这一条项目记忆")), (String[]) null),
                tool("project_memory_write", "往你所在项目的项目记忆里记一条：决策、约定、踩过的坑。项目里所有 Agent 都会看到。"
                                + "用户提醒你「记到项目里」时用它；填 memory_id 则修改已有的那一条。",
                        props(req("title", "string", "一句话标题"), opt("content", "string", "具体内容，简洁"),
                                opt("memory_id", "string", "要修改的那条记忆的 id")), "title"),
                tool("project_update", "直接修改你所在项目的项目说明和项目仓库，不需要先问用户；改完在回复里用一两句话说明改了什么。"
                                + "项目说明只改你传的字段（传空字符串清空该字段）；仓库用 repo_action 新增、修改或删除。"
                                + "只有项目负责人发起的对话或任务才能改，否则会提示需要项目负责人来改；项目归档后只读。",
                        props(opt("goal", "string", "项目目标"), opt("background", "string", "背景"),
                                opt("norms", "string", "规范：大家都要遵守的做法"), opt("glossary", "string", "术语"),
                                opt("repo_action", "string", "仓库操作：add / update / delete；不传则不动仓库"),
                                opt("repo_id", "string", "update / delete 时要改的仓库 id（见 project_context_read）"),
                                opt("repo_name", "string", "仓库名称"),
                                opt("repo_location", "string", "仓库地址：本地路径或 git 地址"),
                                opt("repo_default_branch", "string", "默认分支"),
                                opt("repo_delivery", "string", "交付方式：merge（合并回本地分支）或 pr"),
                                opt("repo_purpose", "string", "用途说明")), (String[]) null));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "项目上下文");
        try {
            return McpToolResult.ok(switch (name) {
                case "project_context_read" -> projects.readContext(grant.agentId(), grant.taskId(), strOrNull(args, "memory_id"));
                case "project_memory_write" -> {
                    ProjectMemory m = projects.writeMemoryAsAgent(grant.agentId(), grant.taskId(), grant.sessionId(), user,
                            strOrNull(args, "memory_id"), require(args, "title"), strOrNull(args, "content"));
                    yield "已记进项目记忆「" + m.title() + "」（id: " + m.id() + "）。";
                }
                case "project_update" -> update(grant, user, args);
                default -> throw new McpToolException("Unknown tool: " + name);
            });
        } catch (IllegalArgumentException | ProjectArchivedException | com.superagent.core.error.NotFoundException
                 | com.superagent.core.error.ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }

    private String update(McpGrants.Grant grant, String user, JsonNode args) {
        List<String> done = new ArrayList<>();
        ProjectRequests.Brief brief = new ProjectRequests.Brief(given(args, "goal"), given(args, "background"),
                given(args, "norms"), given(args, "glossary"));
        String action = strOrNull(args, "repo_action");
        if (brief.goal() == null && brief.background() == null && brief.norms() == null && brief.glossary() == null
                && action == null) {
            throw new McpToolException("没有要改的内容：传项目说明的字段，或 repo_action");
        }
        if (brief.goal() != null || brief.background() != null || brief.norms() != null || brief.glossary() != null) {
            projects.updateBriefAsAgent(grant.agentId(), grant.taskId(), grant.sessionId(), user, brief);
            done.add("已更新项目说明");
        }
        if (action != null) {
            String repoId = strOrNull(args, "repo_id");
            ProjectRequests.Repo repo = new ProjectRequests.Repo(strOrNull(args, "repo_name"), strOrNull(args, "repo_location"),
                    strOrNull(args, "repo_default_branch"), strOrNull(args, "repo_delivery"), strOrNull(args, "repo_purpose"));
            switch (action.strip().toLowerCase(Locale.ROOT)) {
                case "add" -> {
                    ProjectRepo r = projects.saveRepoAsAgent(grant.agentId(), grant.taskId(), grant.sessionId(), user, null, repo);
                    done.add("已新增仓库「" + r.name() + "」（id: " + r.id() + "）");
                }
                case "update" -> {
                    ProjectRepo r = projects.saveRepoAsAgent(grant.agentId(), grant.taskId(), grant.sessionId(), user,
                            requireRepo(repoId), repo);
                    done.add("已修改仓库「" + r.name() + "」");
                }
                case "delete" -> done.add("已删除仓库「"
                        + projects.deleteRepoAsAgent(grant.agentId(), grant.taskId(), user, requireRepo(repoId)) + "」");
                default -> throw new McpToolException("repo_action 只能是 add、update 或 delete");
            }
        }
        return String.join("；", done) + "。";
    }

    /** The field as passed, "" included (it clears); null when left out. */
    private static String given(JsonNode args, String key) {
        JsonNode v = args.get(key);
        return v == null || v.isNull() ? null : v.asText("");
    }

    private static String requireRepo(String repoId) {
        if (repoId == null || repoId.isBlank()) {
            throw new McpToolException("修改或删除仓库要传 repo_id");
        }
        return repoId;
    }
}
