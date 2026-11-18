package com.superagent.project;

import com.superagent.api.project.ProjectBriefDto;
import com.superagent.api.project.ProjectDto;
import com.superagent.api.project.ProjectMemoryDto;
import com.superagent.api.project.ProjectRepoDto;
import com.superagent.api.project.ProjectRequests;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.file.ProjectFolder;
import com.superagent.core.file.ProjectFolderStore;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectBrief;
import com.superagent.core.project.ProjectContextStore;
import com.superagent.core.project.ProjectAccess;
import com.superagent.core.project.ProjectItemKind;
import com.superagent.core.project.ProjectItemStore;
import com.superagent.core.project.ProjectMember;
import com.superagent.core.project.ProjectMemory;
import com.superagent.core.project.ProjectRepo;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Projects: anyone can start one and becomes its owner; the owner names it, adds and removes people, and archives it.
 * Members can use every agent in it and see every task and file made in it. An agent is in at most one project,
 * put there by its owner. Archiving keeps everything; the agents just stop taking work until the project reopens.
 *
 * <p>A project also carries what its agents share: the code repositories it works on, a brief (owner-edited) and a
 * memory (members and agents add to it). Archived, all of it is read-only.
 */
@Service
public class ProjectService implements ProjectAccess {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    static final int NAME_MAX = 80;
    static final int TEXT_MAX = 4000;
    static final int MEMORY_MAX = 2000;

    private final ProjectStore store;
    private final ProjectItemStore items;
    private final ProjectContextStore context;
    private final ProjectFolderStore folders;
    private final WorkFileStore files;
    private final AgentStore agents;
    private final UserStore users;

    public ProjectService(ProjectStore store, ProjectItemStore items, ProjectContextStore context, ProjectFolderStore folders,
                          WorkFileStore files, AgentStore agents, UserStore users) {
        this.store = store;
        this.items = items;
        this.context = context;
        this.folders = folders;
        this.files = files;
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

    // --- repositories ---

    public List<ProjectRepoDto> repos(String id, String user) {
        requireMember(id, user);
        return context.repos(id).stream().map(ProjectService::toDto).toList();
    }

    /** Adds a repository ({@code repoId} null) or changes one. Owner only. */
    public List<ProjectRepoDto> saveRepo(String id, String user, String repoId, ProjectRequests.Repo r) {
        requireWritable(requireOwner(id, user));
        List<ProjectRepo> current = context.repos(id);
        String name = required(r == null ? null : r.name(), "请填写仓库名称", NAME_MAX);
        String location = required(r.location(), "请填写仓库地址（本地路径或 git 地址）", 1024);
        String delivery = blank(r.delivery()) == null ? ProjectRepo.MERGE : r.delivery().strip().toLowerCase();
        if (!ProjectRepo.MERGE.equals(delivery) && !ProjectRepo.PR.equals(delivery)) {
            throw new IllegalArgumentException("交付方式只能是 merge 或 pr");
        }
        Instant now = Instant.now();
        ProjectRepo existing = repoId == null ? null : current.stream().filter(x -> x.id().equals(repoId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有这个仓库"));
        context.saveRepo(new ProjectRepo(existing == null ? UUID.randomUUID().toString() : existing.id(), id, name, location,
                blank(r.defaultBranch()), delivery, limit(blank(r.purpose()), TEXT_MAX, "用途说明"),
                existing == null ? current.stream().mapToInt(ProjectRepo::position).max().orElse(-1) + 1 : existing.position(),
                existing == null ? now : existing.createdAt(), now));
        return repos(id, user);
    }

    public List<ProjectRepoDto> deleteRepo(String id, String user, String repoId) {
        requireWritable(requireOwner(id, user));
        context.deleteRepo(id, repoId);
        return repos(id, user);
    }

    // --- brief ---

    public ProjectBriefDto brief(String id, String user) {
        requireMember(id, user);
        return context.brief(id).map(this::toDto).orElse(new ProjectBriefDto(null, null, null, null, null, null, null));
    }

    public ProjectBriefDto saveBrief(String id, String user, ProjectRequests.Brief b) {
        requireWritable(requireOwner(id, user));
        context.saveBrief(new ProjectBrief(id, limit(blank(b.goal()), TEXT_MAX, "目标"), limit(blank(b.background()), TEXT_MAX, "背景"),
                limit(blank(b.norms()), TEXT_MAX, "规范"), limit(blank(b.glossary()), TEXT_MAX, "术语"), user, Instant.now()));
        return brief(id, user);
    }

    // --- memory ---

    public List<ProjectMemoryDto> memories(String id, String user) {
        requireMember(id, user);
        return context.memories(id).stream().map(this::toDto).toList();
    }

    /** A member writes an entry themselves. */
    public ProjectMemoryDto addMemory(String id, String user, ProjectRequests.Memory m) {
        requireWritable(requireMember(id, user));
        return toDto(writeMemory(id, null, null, null, user, m.title(), m.content()));
    }

    public ProjectMemoryDto updateMemory(String id, String memoryId, String user, ProjectRequests.Memory m) {
        requireWritable(requireMember(id, user));
        ProjectMemory e = memoryIn(id, memoryId);
        ProjectMemory next = e.edited(required(m.title(), "请填写标题", 120), limit(blank(m.content()), MEMORY_MAX, "内容"), user);
        context.saveMemory(next);
        return toDto(next);
    }

    public void deleteMemory(String id, String memoryId, String user) {
        requireWritable(requireMember(id, user));
        context.saveMemory(memoryIn(id, memoryId).asDeleted(user));
    }

    /**
     * An agent writes to (or, with {@code memoryId}, revises an entry of) the memory of the project it's working in:
     * the task's project, else its own. Records which agent, which conversation or task, and who set it off.
     */
    public ProjectMemory writeMemoryAsAgent(String agentId, String taskId, String sessionId, String initiator, String memoryId,
                                            String title, String content) {
        Project p = projectOfRun(agentId, taskId).flatMap(store::find)
                .orElseThrow(() -> new IllegalArgumentException("你不在任何项目里，没有项目记忆可写"));
        requireWritable(p);
        if (memoryId != null) {
            ProjectMemory e = memoryIn(p.id(), memoryId);
            ProjectMemory next = e.edited(required(title, "请填写标题", 120), limit(blank(content), MEMORY_MAX, "内容"), initiator);
            context.saveMemory(next);
            return next;
        }
        return writeMemory(p.id(), agentId, sessionId, taskId, initiator, title, content);
    }

    /** The full shared context of the project this run is in, or one memory entry in full; for the read tool. */
    public String readContext(String agentId, String taskId, String memoryId) {
        Project p = projectOfRun(agentId, taskId).flatMap(store::find)
                .orElseThrow(() -> new IllegalArgumentException("你不在任何项目里，没有项目上下文"));
        if (memoryId != null) {
            ProjectMemory m = memoryIn(p.id(), memoryId);
            return "【" + m.title() + "】\n" + (m.content() == null ? "" : m.content());
        }
        return buildContext(p, Integer.MAX_VALUE);
    }

    private ProjectMemory writeMemory(String projectId, String agentId, String sessionId, String taskId, String by,
                                      String title, String content) {
        Instant now = Instant.now();
        ProjectMemory m = new ProjectMemory(UUID.randomUUID().toString(), projectId, required(title, "请填写标题", 120),
                limit(blank(content), MEMORY_MAX, "内容"), agentId, sessionId, taskId, by, by, false, now, now);
        context.saveMemory(m);
        return m;
    }

    private ProjectMemory memoryIn(String projectId, String memoryId) {
        return context.memory(memoryId).filter(m -> m.projectId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException("没有这条项目记忆"));
    }

    /** The project file migration: files recorded in a project before shared folders existed go to its root. */
    public int mountLegacyFiles() {
        int n = 0;
        for (Project p : store.list()) {
            for (String fileId : items.items(ProjectItemKind.FILE, p.id())) {
                boolean live = files.find(fileId).filter(f -> !f.deleted()).isPresent();
                if (live && !folders.projectsOfFile(fileId).contains(p.id())) {
                    folders.place(p.id(), fileId, "", "migrate");
                    n++;
                }
            }
        }
        return n;
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
        if (user == null || itemId == null) {
            return false;
        }
        List<String> mine = store.projectsOf(user);
        return items.projectOf(kind, itemId).map(mine::contains).orElse(false)
                || (kind == ProjectItemKind.FILE && folders.projectsOfFile(itemId).stream().anyMatch(mine::contains));
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
        // An archived project is read-only: nothing new goes into it, not even an agent's output.
        pid.or(() -> projectOfAgent(agentId)).filter(p -> store.find(p).filter(Project::archived).isEmpty()).ifPresent(p -> {
            items.assign(ProjectItemKind.FILE, fileId, p);
            if (!folders.projectsOfFile(fileId).contains(p)) {
                folders.place(p, fileId, "", "auto"); // into the project's folders, at the root until someone files it
            }
        });
    }

    @Override
    public List<String> itemsInProject(String projectId, ProjectItemKind kind, String user) {
        if (projectId == null || user == null || !store.projectsOf(user).contains(projectId)) {
            return List.of();
        }
        return kind == ProjectItemKind.FILE ? filesIn(projectId) : items.items(kind, projectId);
    }

    @Override
    public List<Project> projectsOf(String user) {
        if (user == null) {
            return List.of();
        }
        Set<String> mine = new LinkedHashSet<>(store.projectsOf(user));
        return store.list().stream().filter(p -> mine.contains(p.id())).toList();
    }

    @Override
    public boolean member(String projectId, String user) {
        return projectId != null && user != null && store.projectsOf(user).contains(projectId);
    }

    @Override
    public boolean projectArchived(String projectId) {
        return projectId != null && store.find(projectId).map(Project::archived).orElse(false);
    }

    @Override
    public void requireWritable(String projectId, String user) {
        requireWritable(requireMember(projectId, user));
    }

    @Override
    public void forgetFile(String projectId, String fileId) {
        if (items.projectOf(ProjectItemKind.FILE, fileId).filter(projectId::equals).isPresent()) {
            items.unassign(ProjectItemKind.FILE, fileId);
        }
    }

    @Override
    public Optional<String> projectOfTask(String taskId) {
        return taskId == null ? Optional.empty() : items.projectOf(ProjectItemKind.TASK, taskId);
    }

    @Override
    public List<ProjectRepo> reposOfAgent(String agentId) {
        return projectOfAgent(agentId).map(context::repos).orElse(List.of());
    }

    @Override
    public Optional<String> context(String agentId, String taskId) {
        return projectOfRun(agentId, taskId).flatMap(store::find).map(p -> buildContext(p, ProjectContextBuilder.MAX));
    }

    private Optional<String> projectOfRun(String agentId, String taskId) {
        return projectOfTask(taskId).or(() -> projectOfAgent(agentId));
    }

    private String buildContext(Project p, int max) {
        Map<String, ProjectFolder> byId = new HashMap<>();
        folders.list(p.id()).forEach(f -> byId.put(f.id(), f));
        List<ProjectContextBuilder.FileEntry> entries = new ArrayList<>();
        folders.placements(p.id()).forEach((fileId, folderId) -> files.find(fileId).filter(f -> !f.deleted()).ifPresent(f -> {
            String where = folderPath(byId.get(folderId), byId);
            String note = String.join(" · ", java.util.stream.Stream.of(where.isEmpty() ? null : "目录 " + where,
                    f.tags().isEmpty() ? null : "标签 " + String.join("、", f.tags())).filter(Objects::nonNull).toList());
            entries.add(new ProjectContextBuilder.FileEntry(f.id(), f.name(), note));
        }));
        return ProjectContextBuilder.build(p.name(), context.brief(p.id()).orElse(null), entries, context.repos(p.id()),
                context.memories(p.id()), max);
    }

    private static String folderPath(ProjectFolder f, Map<String, ProjectFolder> byId) {
        List<String> names = new ArrayList<>();
        for (ProjectFolder at = f; at != null; at = at.parentId() == null ? null : byId.get(at.parentId())) {
            names.add(0, at.name());
        }
        return String.join("/", names);
    }

    /** Files mounted in the project's folders, plus any recorded there before folders existed. */
    private List<String> filesIn(String projectId) {
        Set<String> out = new LinkedHashSet<>(folders.placements(projectId).keySet());
        out.addAll(items.items(ProjectItemKind.FILE, projectId));
        return new ArrayList<>(out);
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

    private static Project requireWritable(Project p) {
        if (p.archived()) {
            throw new ProjectArchivedException();
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
                items.items(ProjectItemKind.TASK, p.id()).size(), filesIn(p.id()).size(),
                p.createdAt(), p.updatedAt(), p.archivedAt());
    }

    private static ProjectRepoDto toDto(ProjectRepo r) {
        return new ProjectRepoDto(r.id(), r.name(), r.location(), r.defaultBranch(), r.delivery(), r.purpose());
    }

    private ProjectBriefDto toDto(ProjectBrief b) {
        return new ProjectBriefDto(b.goal(), b.background(), b.norms(), b.glossary(), b.updatedBy(),
                b.updatedBy() == null ? null : displayName(b.updatedBy()), b.updatedAt());
    }

    private ProjectMemoryDto toDto(ProjectMemory m) {
        String agentName = m.agentId() == null ? null : agents.find(m.agentId()).map(Agent::name).orElse(null);
        return new ProjectMemoryDto(m.id(), m.title(), m.content(), m.agentId(), agentName, m.sessionId(), m.taskId(),
                m.createdBy(), m.createdBy() == null ? null : displayName(m.createdBy()), m.updatedBy(), m.createdAt(), m.updatedAt());
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

    private static String required(String s, String message, int max) {
        String v = blank(s);
        if (v == null) {
            throw new IllegalArgumentException(message);
        }
        if (v.length() > max) {
            throw new IllegalArgumentException("最多 " + max + " 个字：" + v.substring(0, Math.min(20, v.length())) + "…");
        }
        return v;
    }

    private static String limit(String s, int max, String what) {
        if (s != null && s.length() > max) {
            throw new IllegalArgumentException(what + "最多 " + max + " 个字");
        }
        return s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
