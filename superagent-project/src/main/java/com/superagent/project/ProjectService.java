package com.superagent.project;

import com.superagent.api.project.ProjectDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectAccess;
import com.superagent.core.project.ProjectItemKind;
import com.superagent.core.project.ProjectItemStore;
import com.superagent.core.project.ProjectMember;
import com.superagent.core.project.ProjectStatus;
import com.superagent.core.project.ProjectStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Projects: anyone can start one and becomes its owner; the owner names it, adds and removes people, and archives it.
 * Members can use every agent in it and see every task and file made in it. An agent is in at most one project,
 * put there by its owner. Archiving keeps everything; the agents just stop taking work until the project reopens.
 */
@Service
public class ProjectService implements ProjectAccess {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    static final int NAME_MAX = 80;

    private final ProjectStore store;
    private final ProjectItemStore items;
    private final AgentStore agents;
    private final UserStore users;

    public ProjectService(ProjectStore store, ProjectItemStore items, AgentStore agents, UserStore users) {
        this.store = store;
        this.items = items;
        this.agents = agents;
        this.users = users;
    }

    // --- projects ---

    public ProjectDto create(String owner, String name, String description) {
        Instant now = Instant.now();
        Project p = new Project(UUID.randomUUID().toString(), name(name), blank(description), owner, ProjectStatus.ACTIVE,
                now, now, null);
        store.save(p);
        store.saveMember(new ProjectMember(p.id(), owner, owner, true, now, now));
        log.info("project {} created by {}", p.id(), owner);
        return toDto(p, owner);
    }

    /** The user's projects: active ones first, then archived, most recently changed first. */
    public List<ProjectDto> list(String user) {
        Set<String> mine = new LinkedHashSet<>(store.projectsOf(user));
        return store.list().stream().filter(p -> mine.contains(p.id()))
                .sorted(Comparator.comparing(Project::archived).thenComparing(Project::updatedAt, Comparator.reverseOrder()))
                .map(p -> toDto(p, user)).toList();
    }

    public ProjectDto get(String id, String user) {
        return toDto(requireMember(id, user), user);
    }

    public ProjectDto update(String id, String user, String name, String description) {
        Project p = requireOwner(id, user).withDetails(name(name), blank(description));
        store.save(p);
        return toDto(p, user);
    }

    public ProjectDto archive(String id, String user) {
        return setStatus(id, user, ProjectStatus.ARCHIVED);
    }

    public ProjectDto reopen(String id, String user) {
        return setStatus(id, user, ProjectStatus.ACTIVE);
    }

    private ProjectDto setStatus(String id, String user, ProjectStatus status) {
        Project p = requireOwner(id, user);
        if (p.status() != status) {
            p = p.withStatus(status);
            store.save(p);
            log.info("project {} {} by {}", id, status == ProjectStatus.ARCHIVED ? "archived" : "reopened", user);
        }
        return toDto(p, user);
    }

    // --- members ---

    public ProjectDto addMembers(String id, String owner, List<String> usernames) {
        Project p = requireOwner(id, owner);
        Set<String> current = new LinkedHashSet<>(memberNames(id));
        Instant now = Instant.now();
        for (String who : usernames == null ? List.<String>of() : usernames) {
            String key = who == null ? "" : who.strip();
            // Named by user id (the picker) or username; kept as the id.
            User u = users.findById(key).or(() -> users.findByUsername(key))
                    .orElseThrow(() -> new IllegalArgumentException("没有这个用户：" + who));
            if (current.add(u.id())) {
                store.saveMember(new ProjectMember(id, u.id(), owner, true, now, now));
            }
        }
        return toDto(p, owner);
    }

    public ProjectDto removeMember(String id, String owner, String username) {
        Project p = requireOwner(id, owner);
        if (Objects.equals(p.owner(), username)) {
            throw new IllegalArgumentException("负责人不能移出自己的项目");
        }
        store.members(id).stream().filter(m -> m.username().equals(username)).findFirst()
                .ifPresent(m -> store.saveMember(new ProjectMember(id, username, m.addedBy(), false, m.createdAt(), Instant.now())));
        return toDto(p, owner);
    }

    // --- agents ---

    /**
     * Moves the user's own agent into a project they are in (or out of any project when {@code projectId} is null).
     * An agent is in at most one project; the SuperAgent is each person's own and never in one.
     */
    public void setAgentProject(String agentId, String user, String projectId) {
        Agent a = agents.find(agentId).filter(x -> x.status() != AgentStatus.DELETED)
                .orElseThrow(() -> new IllegalArgumentException("没有这个 Agent：" + agentId));
        if (!Objects.equals(a.owner(), user)) {
            throw new ProjectForbiddenException("只有 Agent 的所有者能把它放进或移出项目");
        }
        if (a.isSuper()) {
            throw new IllegalArgumentException("SuperAgent 不能放进项目");
        }
        if (projectId == null || projectId.isBlank()) {
            items.unassign(ProjectItemKind.AGENT, agentId);
            return;
        }
        Project p = requireMember(projectId, user);
        if (p.archived()) {
            throw new IllegalArgumentException("项目已归档，不能再放入 Agent");
        }
        items.assign(ProjectItemKind.AGENT, agentId, projectId);
    }

    // --- ProjectAccess ---

    @Override
    public void placeAgent(String agentId, String user, String projectId) {
        setAgentProject(agentId, user, projectId);
    }

    @Override
    public List<String> agentsThroughProjects(String user) {
        if (user == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        store.projectsOf(user).forEach(pid -> out.addAll(items.items(ProjectItemKind.AGENT, pid)));
        return out;
    }

    @Override
    public boolean sharesProject(String agentId, String user) {
        return user != null && projectOfAgent(agentId).map(pid -> store.projectsOf(user).contains(pid)).orElse(false);
    }

    @Override
    public Optional<String> projectOfAgent(String agentId) {
        return agentId == null ? Optional.empty() : items.projectOf(ProjectItemKind.AGENT, agentId);
    }

    @Override
    public boolean archived(String agentId) {
        return projectOfAgent(agentId).flatMap(store::find).map(Project::archived).orElse(false);
    }

    @Override
    public boolean visibleThroughProject(ProjectItemKind kind, String itemId, String user) {
        return user != null && itemId != null
                && items.projectOf(kind, itemId).map(pid -> store.projectsOf(user).contains(pid)).orElse(false);
    }

    @Override
    public void recordTask(String taskId, String agentId) {
        if (taskId != null) {
            projectOfAgent(agentId).ifPresent(pid -> items.assign(ProjectItemKind.TASK, taskId, pid));
        }
    }

    @Override
    public void recordFile(String fileId, String agentId, String taskId) {
        if (fileId == null) {
            return;
        }
        Optional<String> pid = taskId == null ? Optional.empty() : items.projectOf(ProjectItemKind.TASK, taskId);
        pid.or(() -> projectOfAgent(agentId)).ifPresent(p -> items.assign(ProjectItemKind.FILE, fileId, p));
    }

    @Override
    public List<String> itemsInProject(String projectId, ProjectItemKind kind, String user) {
        if (projectId == null || user == null || !store.projectsOf(user).contains(projectId)) {
            return List.of();
        }
        return items.items(kind, projectId);
    }

    // --- helpers ---

    private Project require(String id) {
        return store.find(id).orElseThrow(() -> new ProjectNotFoundException(id));
    }

    private Project requireMember(String id, String user) {
        Project p = require(id);
        if (user == null || !store.projectsOf(user).contains(id)) {
            throw new ProjectNotFoundException(id); // someone else's project reads as "not found"
        }
        return p;
    }

    private Project requireOwner(String id, String user) {
        Project p = requireMember(id, user);
        if (!Objects.equals(p.owner(), user)) {
            throw new ProjectForbiddenException("只有项目负责人能做这个操作");
        }
        return p;
    }

    private List<String> memberNames(String id) {
        return store.members(id).stream().map(ProjectMember::username).toList();
    }

    private ProjectDto toDto(Project p, String viewer) {
        List<ProjectDto.Member> members = store.members(p.id()).stream()
                .sorted(Comparator.comparing((ProjectMember m) -> !m.username().equals(p.owner())))
                .map(m -> new ProjectDto.Member(m.username(), displayName(m.username()), m.username().equals(p.owner())))
                .toList();
        List<String> agentIds = items.items(ProjectItemKind.AGENT, p.id()).stream()
                .filter(id -> agents.find(id).filter(a -> a.status() != AgentStatus.DELETED).isPresent()).toList();
        return new ProjectDto(p.id(), p.name(), p.description(), p.owner(), displayName(p.owner()),
                p.status().name().toLowerCase(), Objects.equals(p.owner(), viewer), members, agentIds,
                items.items(ProjectItemKind.TASK, p.id()).size(), items.items(ProjectItemKind.FILE, p.id()).size(),
                p.createdAt(), p.updatedAt(), p.archivedAt());
    }

    private String displayName(String userId) {
        return users.findById(userId).map(u -> u.displayName() != null && !u.displayName().isBlank() ? u.displayName()
                : u.username()).orElse(userId);
    }

    private static String name(String name) {
        String n = blank(name);
        if (n == null) {
            throw new IllegalArgumentException("请填写项目名称");
        }
        if (n.length() > NAME_MAX) {
            throw new IllegalArgumentException("项目名称最多 " + NAME_MAX + " 个字");
        }
        return n;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
