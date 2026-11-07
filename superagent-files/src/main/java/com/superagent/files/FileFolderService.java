package com.superagent.files;

import com.superagent.api.file.FileFolderDto;
import com.superagent.core.file.FileFolder;
import com.superagent.core.file.FileFolderStore;
import com.superagent.core.file.WorkFile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Each person's folders in Files, and where they keep each file: a person's own tree, with files going in three
 * ways: moved by the person, named by the agent that delivered them, or picked by the auto-filer among the folders
 * that already exist. Deleting a folder never deletes files: they and its subfolders go up a level.
 */
@Service
public class FileFolderService {

    static final int MAX_DEPTH = 3;
    private static final int MAX_NAME = 60;
    private static final String UNFILED = "";

    private final FileFolderStore store;
    private final FileService files;

    public FileFolderService(FileFolderStore store, FileService files) {
        this.store = store;
        this.files = files;
    }

    public List<FileFolderDto> list(String user) {
        Map<String, FileFolder> all = byId(user);
        return all.values().stream().map(f -> new FileFolderDto(f.id(), f.name(), f.parentId(), path(f, all)))
                .sorted(Comparator.comparing(FileFolderDto::path)).toList();
    }

    public FileFolderDto create(String user, String name, String parentId) {
        String clean = cleanName(name);
        Map<String, FileFolder> all = byId(user);
        String parent = parentId == null || parentId.isBlank() ? null : mine(parentId, user).id();
        if (parent != null && depth(all.get(parent), all) >= MAX_DEPTH) {
            throw new IllegalArgumentException("目录最多 " + MAX_DEPTH + " 层");
        }
        requireFreeName(all, clean, parent, null);
        Instant now = Instant.now();
        FileFolder f = new FileFolder(UUID.randomUUID().toString(), user, clean, parent, false, now, now);
        store.save(f);
        all.put(f.id(), f);
        return new FileFolderDto(f.id(), f.name(), f.parentId(), path(f, all));
    }

    public FileFolderDto rename(String user, String id, String name) {
        FileFolder f = mine(id, user);
        String clean = cleanName(name);
        Map<String, FileFolder> all = byId(user);
        requireFreeName(all, clean, f.parentId(), f.id());
        FileFolder renamed = f.withName(clean);
        store.save(renamed);
        all.put(id, renamed);
        return new FileFolderDto(id, clean, f.parentId(), path(renamed, all));
    }

    public void delete(String user, String id) {
        FileFolder f = mine(id, user);
        Map<String, FileFolder> all = byId(user);
        for (FileFolder child : all.values()) {
            if (id.equals(child.parentId())) {
                FileFolder moved = child.withParent(f.parentId());
                String name = child.name();
                for (int n = 2; taken(all, name, f.parentId(), child.id()); n++) {
                    name = child.name() + " (" + n + ")"; // the level above already has one by this name
                }
                store.save(name.equals(child.name()) ? moved : moved.withName(name));
            }
        }
        String up = f.parentId() == null ? UNFILED : f.parentId();
        store.placements(user).forEach((fileId, folderId) -> {
            if (id.equals(folderId)) {
                store.place(user, fileId, up, "user");
            }
        });
        store.save(f.asDeleted());
    }

    /** A person moves a file they can see into one of their folders (blank = unfiled). */
    public void move(String user, String fileId, String folderId) {
        files.get(fileId, user);
        store.place(user, fileId, folderId == null || folderId.isBlank() ? UNFILED : mine(folderId, user).id(), "user");
    }

    // --- agents: the same things, by path ---

    public void moveToPath(String user, String fileId, String path) {
        move(user, fileId, path == null || path.isBlank() ? null : requirePath(user, path).id());
    }

    public String renamePath(String user, String path, String name) {
        return rename(user, requirePath(user, path).id(), name).path();
    }

    public void deletePath(String user, String path) {
        delete(user, requirePath(user, path).id());
    }

    /** Moves a folder with all that's in it under {@code parentPath} (blank = the top level). */
    public String movePath(String user, String path, String parentPath) {
        FileFolder f = requirePath(user, path);
        String parent = parentPath == null || parentPath.isBlank() ? null : requirePath(user, parentPath).id();
        Map<String, FileFolder> all = byId(user);
        for (FileFolder at = parent == null ? null : all.get(parent); at != null; at = at.parentId() == null ? null : all.get(at.parentId())) {
            if (at.id().equals(f.id())) {
                throw new IllegalArgumentException("不能把目录移到它自己里面");
            }
        }
        if ((parent == null ? 0 : depth(all.get(parent), all)) + height(f, all) > MAX_DEPTH) {
            throw new IllegalArgumentException("移动后会超过 " + MAX_DEPTH + " 层");
        }
        requireFreeName(all, f.name(), parent, f.id());
        FileFolder moved = f.withParent(parent);
        store.save(moved);
        all.put(f.id(), moved);
        return path(moved, all);
    }

    private FileFolder requirePath(String user, String path) {
        return byPath(user, path).orElseThrow(() -> new IllegalArgumentException("没有目录「" + path + "」"));
    }

    private static int height(FileFolder f, Map<String, FileFolder> all) {
        return 1 + all.values().stream().filter(c -> f.id().equals(c.parentId())).mapToInt(c -> height(c, all)).max().orElse(0);
    }

    /** The owner's folders as paths, for telling an agent or the auto-filer what exists. */
    public List<String> paths(String owner) {
        return list(owner).stream().map(FileFolderDto::path).toList();
    }

    /** Files what an agent just delivered in the folder it named; false when there's no such folder. */
    public boolean placeByAgent(String owner, String fileId, String path) {
        Optional<FileFolder> folder = byPath(owner, path);
        folder.ifPresent(f -> store.place(owner, fileId, f.id(), "agent"));
        return folder.isPresent();
    }

    /** Creates {@code path} and whatever above it is missing; returns the full path. */
    public String createPath(String owner, String path) {
        String parent = null;
        String done = "";
        for (String part : path == null ? new String[0] : path.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            done = done.isEmpty() ? part.strip() : done + "/" + part.strip();
            Optional<FileFolder> existing = byPath(owner, done);
            parent = existing.isPresent() ? existing.get().id() : create(owner, part, parent).id();
        }
        if (done.isEmpty()) {
            throw new IllegalArgumentException("A folder needs a name");
        }
        return done;
    }

    private Optional<FileFolder> byPath(String owner, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String wanted = String.join("/", java.util.Arrays.stream(path.split("/")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        Map<String, FileFolder> all = byId(owner);
        return all.values().stream().filter(f -> path(f, all).equals(wanted)).findFirst();
    }

    // --- the auto-filer ---

    public record Unfiled(WorkFile file, String excerpt) {
    }

    public List<String> owners() {
        return store.owners();
    }

    /**
     * The owner's own files nobody has placed yet, oldest first. Only those made since their first folder: making a
     * folder isn't a request to re-sort everything that came before, and it would be one model call per batch of it.
     */
    public List<Unfiled> unfiled(String owner, int limit) {
        Instant since = store.list(owner).stream().map(FileFolder::createdAt).min(Comparator.naturalOrder()).orElse(null);
        if (since == null) {
            return List.of();
        }
        Map<String, String> placed = store.placements(owner);
        return files.ownedBy(owner).stream()
                .filter(f -> !placed.containsKey(f.id()) && !f.createdAt().isBefore(since))
                .sorted(Comparator.comparing(WorkFile::createdAt)).limit(limit)
                .map(f -> new Unfiled(f, files.excerpt(f))).toList();
    }

    /** The auto-filer's answer: a folder that exists, or anything else (including none) stays unfiled and isn't asked again. */
    public void autoFile(String owner, String fileId, String path) {
        if (!store.placements(owner).containsKey(fileId)) { // a person or agent may have got there first
            store.place(owner, fileId, byPath(owner, path).map(FileFolder::id).orElse(UNFILED), "auto");
        }
    }

    // --- helpers ---

    private FileFolder mine(String id, String user) {
        // Not "forbidden": someone else's folder doesn't exist as far as this person can tell.
        return store.find(id).filter(f -> f.owner().equals(user))
                .orElseThrow(() -> new IllegalArgumentException("没有这个目录"));
    }

    private Map<String, FileFolder> byId(String user) {
        Map<String, FileFolder> out = new LinkedHashMap<>();
        store.list(user).forEach(f -> out.put(f.id(), f));
        return out;
    }

    private static String path(FileFolder f, Map<String, FileFolder> all) {
        List<String> names = new ArrayList<>();
        for (FileFolder at = f; at != null; at = at.parentId() == null ? null : all.get(at.parentId())) {
            names.add(0, at.name());
        }
        return String.join("/", names);
    }

    private static int depth(FileFolder f, Map<String, FileFolder> all) {
        int d = 0;
        for (FileFolder at = f; at != null; at = at.parentId() == null ? null : all.get(at.parentId())) {
            d++;
        }
        return d;
    }

    private static boolean taken(Map<String, FileFolder> all, String name, String parent, String except) {
        return all.values().stream().anyMatch(f -> !f.id().equals(except) && f.name().equals(name)
                && java.util.Objects.equals(f.parentId(), parent));
    }

    private static void requireFreeName(Map<String, FileFolder> all, String name, String parent, String except) {
        if (taken(all, name, parent, except)) {
            throw new IllegalArgumentException("这一层已经有叫「" + name + "」的目录了");
        }
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("目录名不能为空");
        }
        if (clean.contains("/") || clean.contains("\\")) {
            throw new IllegalArgumentException("目录名不能包含 / 或 \\");
        }
        if (clean.length() > MAX_NAME) {
            throw new IllegalArgumentException("目录名最多 " + MAX_NAME + " 个字符");
        }
        return clean;
    }
}
