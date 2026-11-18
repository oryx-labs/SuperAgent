package com.superagent.files;

import com.superagent.api.file.FileFolderDto;
import com.superagent.api.file.ProjectTreeDto;
import com.superagent.core.file.ProjectFolder;
import com.superagent.core.file.ProjectFolderStore;
import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Each project's shared folder tree in Files, next to everyone's own. A file is stored once and mounted where it shows
 * up: in any number of projects (once each), besides the agent that made it and people's own folders. Members add
 * and take out files and folders; taking a file out only drops the mount. Mounting needs someone who can see the file
 * already (through its owner, agent or another project) and is in the target project. Archived projects are read-only.
 */
@Service
public class ProjectFolderService {

    private static final String ROOT = "";

    private final ProjectFolderStore store;
    private final FileService files;
    private ProjectAccess projects; // null in tests and wherever projects aren't wired: no project folders

    public ProjectFolderService(ProjectFolderStore store, FileService files) {
        this.store = store;
        this.files = files;
    }

    @Autowired(required = false)
    void setProjects(ProjectAccess projects) {
        this.projects = projects;
    }

    /** The trees of every project the user is in. */
    public List<ProjectTreeDto> trees(String user) {
        if (projects == null) {
            return List.of();
        }
        return projects.projectsOf(user).stream().sorted(Comparator.comparing(Project::archived).thenComparing(Project::name))
                .map(this::tree).toList();
    }

    public FileFolderDto create(String user, String projectId, String name, String parentId) {
        projects().requireWritable(projectId, user);
        String clean = FileFolderService.cleanName(name);
        Map<String, ProjectFolder> all = byId(projectId);
        String parent = parentId == null || parentId.isBlank() ? null : inProject(parentId, projectId).id();
        if (parent != null && depth(all.get(parent), all) >= FileFolderService.MAX_DEPTH) {
            throw new IllegalArgumentException("目录最多 " + FileFolderService.MAX_DEPTH + " 层");
        }
        requireFreeName(all, clean, parent, null);
        Instant now = Instant.now();
        ProjectFolder f = new ProjectFolder(UUID.randomUUID().toString(), projectId, clean, parent, false, now, now);
        store.save(f);
        all.put(f.id(), f);
        return dto(f, all);
    }

    public FileFolderDto rename(String user, String folderId, String name) {
        ProjectFolder f = writableFolder(folderId, user);
        String clean = FileFolderService.cleanName(name);
        Map<String, ProjectFolder> all = byId(f.projectId());
        requireFreeName(all, clean, f.parentId(), f.id());
        ProjectFolder renamed = f.withName(clean);
        store.save(renamed);
        all.put(renamed.id(), renamed);
        return dto(renamed, all);
    }

    /** Its files and subfolders go up a level; no file is deleted or unmounted. */
    public void delete(String user, String folderId) {
        ProjectFolder f = writableFolder(folderId, user);
        Map<String, ProjectFolder> all = byId(f.projectId());
        for (ProjectFolder child : all.values()) {
            if (folderId.equals(child.parentId())) {
                String name = child.name();
                for (int n = 2; taken(all, name, f.parentId(), child.id()); n++) {
                    name = child.name() + " (" + n + ")";
                }
                store.save(child.withParent(f.parentId()).withName(name));
            }
        }
        String up = f.parentId() == null ? ROOT : f.parentId();
        store.placements(f.projectId()).forEach((fileId, at) -> {
            if (folderId.equals(at)) {
                store.place(f.projectId(), fileId, up, "user");
            }
        });
        store.save(f.asDeleted());
    }

    /** Mounts a file the user can see in a project they're in, in {@code folderId} (blank = its root); moves it if already there. */
    public void mount(String user, String fileId, String projectId, String folderId) {
        files.get(fileId, user); // can't put what you can't see
        projects().requireWritable(projectId, user);
        String folder = folderId == null || folderId.isBlank() ? ROOT : inProject(folderId, projectId).id();
        store.place(projectId, fileId, folder, "user");
    }

    /** Takes the file out of the project's folders; the file itself stays wherever else it is. */
    public void unmount(String user, String fileId, String projectId) {
        projects().requireWritable(projectId, user);
        store.unplace(projectId, fileId);
        projects.forgetFile(projectId, fileId);
    }

    // --- agents ---

    /** The project an agent's run works in: its task's, else the agent's own. */
    public Optional<String> projectOfRun(String agentId, String taskId) {
        if (projects == null) {
            return Optional.empty();
        }
        return projects.projectOfTask(taskId).or(() -> projects.projectOfAgent(agentId));
    }

    /**
     * Files what an agent just delivered in the project folder it named; false when the project has no such folder or
     * is archived (read-only: nothing goes in).
     */
    public boolean placeByAgent(String projectId, String fileId, String path) {
        if (projects == null || projects.projectArchived(projectId)) {
            return false;
        }
        Optional<ProjectFolder> f = byPath(projectId, path);
        f.ifPresent(x -> store.place(projectId, fileId, x.id(), "agent"));
        return f.isPresent();
    }

    public List<String> paths(String projectId) {
        Map<String, ProjectFolder> all = byId(projectId);
        return all.values().stream().map(f -> path(f, all)).sorted().toList();
    }

    // --- helpers ---

    private ProjectTreeDto tree(Project p) {
        Map<String, ProjectFolder> all = byId(p.id());
        List<FileFolderDto> folders = all.values().stream().map(f -> dto(f, all)).sorted(Comparator.comparing(FileFolderDto::path)).toList();
        return new ProjectTreeDto(p.id(), p.name(), p.archived(), !p.archived(), folders, store.placements(p.id()));
    }

    private ProjectAccess projects() {
        if (projects == null) {
            throw new IllegalArgumentException("没有项目");
        }
        return projects;
    }

    private ProjectFolder writableFolder(String folderId, String user) {
        ProjectFolder f = store.find(folderId).orElseThrow(() -> new IllegalArgumentException("没有这个目录"));
        if (!projects().member(f.projectId(), user)) {
            throw new IllegalArgumentException("没有这个目录"); // someone else's project doesn't exist as far as this person can tell
        }
        projects.requireWritable(f.projectId(), user);
        return f;
    }

    private ProjectFolder inProject(String folderId, String projectId) {
        return store.find(folderId).filter(f -> f.projectId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException("项目里没有这个目录"));
    }

    private Optional<ProjectFolder> byPath(String projectId, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String wanted = String.join("/", Arrays.stream(path.split("/")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        Map<String, ProjectFolder> all = byId(projectId);
        return all.values().stream().filter(f -> path(f, all).equals(wanted)).findFirst();
    }

    private Map<String, ProjectFolder> byId(String projectId) {
        Map<String, ProjectFolder> out = new LinkedHashMap<>();
        store.list(projectId).forEach(f -> out.put(f.id(), f));
        return out;
    }

    private static FileFolderDto dto(ProjectFolder f, Map<String, ProjectFolder> all) {
        return new FileFolderDto(f.id(), f.name(), f.parentId(), path(f, all));
    }

    private static String path(ProjectFolder f, Map<String, ProjectFolder> all) {
        List<String> names = new ArrayList<>();
        for (ProjectFolder at = f; at != null; at = at.parentId() == null ? null : all.get(at.parentId())) {
            names.add(0, at.name());
        }
        return String.join("/", names);
    }

    private static int depth(ProjectFolder f, Map<String, ProjectFolder> all) {
        int d = 0;
        for (ProjectFolder at = f; at != null; at = at.parentId() == null ? null : all.get(at.parentId())) {
            d++;
        }
        return d;
    }

    private static boolean taken(Map<String, ProjectFolder> all, String name, String parent, String except) {
        return all.values().stream().anyMatch(f -> !f.id().equals(except) && f.name().equals(name) && Objects.equals(f.parentId(), parent));
    }

    private static void requireFreeName(Map<String, ProjectFolder> all, String name, String parent, String except) {
        if (taken(all, name, parent, except)) {
            throw new IllegalArgumentException("这一层已经有叫「" + name + "」的目录了");
        }
    }
}
