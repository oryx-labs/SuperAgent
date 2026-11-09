package com.superagent.files;

import com.superagent.api.file.WorkFileDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.chat.ImageAttachment;
import com.superagent.core.file.FileStore;
import com.superagent.core.file.FileFolderStore;
import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileKind;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskStore;
import com.superagent.core.tenant.TenantPaths;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Work files: what agents produce and what people send them. Content lives on
 * this server under {@code <dir>/<companyId>/<id>/content}; only the latest version is kept, so an agent revising a file
 * overwrites it in place — and only when it names that file explicitly, so two pieces of work that happen to
 * share a name never clobber each other.
 *
 * <p>Files people attach to a message live here too ({@link #upload}, origin {@code upload}), filed under the agent
 * (or task) they were sent to, so everything a conversation touched can be found again with that agent.
 *
 * <p>Who sees a file: whoever gave the instruction (its owner) and the members of the agent that made it.
 * Who may rename or delete it: the owner and the agent's owner.
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final WorkFileStore store;
    private final AgentStore agents;
    private final AgentMemberStore members;
    private final TaskStore tasks;
    private final TaskAgentStore taskAgents;
    private final UserStore users;
    private final FileStorageService storage;
    /** Staging for uploads, and a cache of cloud-stored content an agent or the model needs as a local file. */
    private final Path root;
    private final long maxBytes;

    /**
     * Who is producing a file, and where: {@code owner} gave the instruction; {@code sessionId}/{@code messageId}
     * are set for a chat reply, {@code taskId} for a task run.
     */
    public record Origin(String owner, String agentId, String taskId, String sessionId, String messageId,
                         String origin) {
    }

    /** A file's content for serving: a re-openable {@link Resource}, so ranges (video seeking) work from any store. */
    public record Content(Resource resource, String name, String mimeType) {
    }

    /** A file attached to a message, as a run needs it. */
    public record Attachment(String name, String mimeType, Path content) {
    }

    /** What a run gets from its message's attachments: images for the model to see, and a note for the prompt. */
    public record Delivered(List<ImageAttachment> images, String note) {
    }

    public static final long UPLOAD_MAX_BYTES = 50L * 1024 * 1024;
    public static final int UPLOAD_MAX_FILES = 10;
    /** Where attachments land in a run's workspace, for the agent to read with its own tools. */
    public static final String UPLOADS_DIR = "uploads";
    private static final Set<String> MODEL_IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final FileFolderStore folders;

    public FileService(WorkFileStore store, AgentStore agents, AgentMemberStore members, TaskStore tasks,
                       TaskAgentStore taskAgents, UserStore users, FileStorageService storage, FileFolderStore folders,
                       @Value("${superagent.files.dir:./data/files}") String dir,
                       @Value("${superagent.files.max-mb:200}") long maxMb) {
        this.store = store;
        this.storage = storage;
        this.folders = folders;
        this.agents = agents;
        this.members = members;
        this.tasks = tasks;
        this.taskAgents = taskAgents;
        this.users = users;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.maxBytes = Math.max(1, maxMb) * 1024 * 1024;
    }

    // --- producing files (agent tools) ---

    public WorkFile writeText(Origin origin, String name, String content, String replaceId) {
        return writeText(origin, name, content, replaceId, null);
    }

    /** {@code tags}: the agent's topic tags for the file (null = none given; the tagger fills them in later). */
    public WorkFile writeText(Origin origin, String name, String content, String replaceId, List<String> tags) {
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        return tagged(store(origin, cleanName(name), replaceId, target -> Files.write(target, bytes)), tags);
    }

    public WorkFile saveFrom(Origin origin, Path source, String name, String replaceId, List<String> tags) {
        return tagged(saveFrom(origin, source, name, replaceId), tags);
    }

    public WorkFile saveFrom(Origin origin, Path source, String name, String replaceId) {
        String fileName = cleanName(name == null || name.isBlank() ? source.getFileName().toString() : name);
        return store(origin, fileName, replaceId, target -> {
            if (Files.size(source) > maxBytes) {
                throw new IllegalArgumentException("文件超过 " + maxBytes / 1024 / 1024 + " MB 上限");
            }
            try (InputStream in = Files.newInputStream(source)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    /** The agent's tags on a file it delivered — unless a person has set the file's tags, which then stay. */
    private WorkFile tagged(WorkFile f, List<String> tags) {
        List<String> clean = WorkFile.cleanTags(tags);
        if (clean.isEmpty() || WorkFile.TAGS_USER.equals(f.tagState())) {
            return f;
        }
        WorkFile t = f.withTags(clean, WorkFile.TAGS_AGENT);
        store.save(t);
        return t;
    }

    /** Every tag on the files {@code user} can see, most used first — the vocabulary agents should reuse. */
    public List<String> tagsInUse(String user, String agentId) {
        List<WorkFile> files = agentId != null ? store.listFor(user, List.of(agentId)) : store.listFor(user, accessibleAgents(user));
        Map<String, Integer> counts = new java.util.HashMap<>();
        files.forEach(f -> f.tags().forEach(t -> counts.merge(t, 1, Integer::sum)));
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    /** A person sets the tags: they win over the agent's and the tagger's from now on. */
    public WorkFileDto setTags(String id, String user, List<String> tags) {
        store.save(manageable(id, user).withTags(tags, WorkFile.TAGS_USER));
        return get(id, user);
    }

    /** The tagger's result for a file it was given; skipped if someone tagged the file meanwhile. */
    public void autoTag(String id, List<String> tags) {
        store.find(id).filter(f -> WorkFile.TAGS_PENDING.equals(f.tagState()))
                .ifPresent(f -> store.save(f.withTags(tags, WorkFile.TAGS_AUTO)));
    }

    /** Files waiting for the tagger, with what it needs to read: the start of a text file's content. */
    public record Untagged(WorkFile file, String excerpt, List<String> vocabulary) {
    }

    public List<Untagged> untagged(int limit) {
        List<Untagged> out = new ArrayList<>();
        for (WorkFile f : store.untagged(limit)) {
            out.add(new Untagged(f, excerpt(f), tagsInUse(f.owner(), f.agentId())));
        }
        return out;
    }

    /** The owner's own files (not the ones they merely can see through an agent), for the auto-filer. */
    List<WorkFile> ownedBy(String owner) {
        return store.listFor(owner, List.of());
    }

    String excerpt(WorkFile f) {
        if (WorkFile.LINK.equals(f.storage())) {
            return f.location(); // the address says what it's about
        }
        if (f.kind() != WorkFileKind.DOCUMENT || f.size() > 2_000_000 || !(f.mimeType().startsWith("text/")
                || f.name().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt|csv|json|html?)$"))) {
            return null;
        }
        try {
            String text;
            try (InputStream in = openContent(f)) {
                text = new String(in.readNBytes(6000), StandardCharsets.UTF_8);
            }
            return text.length() <= 1500 ? text : text.substring(0, 1500);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Task tools: the task's agent (if one set it off) is the file's agent, so its members see it too. */
    public Origin forTask(String owner, String taskId) {
        return new Origin(owner, taskAgents.agentOf(taskId).orElse(null), taskId, null, null, WorkFile.ORIGIN_TASK);
    }

    private interface Writer {
        void write(Path target) throws IOException;
    }

    private WorkFile store(Origin origin, String name, String replaceId, Writer writer) {
        return store(origin, name, replaceId == null || replaceId.isBlank() ? null : replaceable(replaceId.strip(), origin), writer);
    }

    private WorkFile store(Origin origin, String name, WorkFile existing, Writer writer) {
        String id = existing != null ? existing.id() : UUID.randomUUID().toString();
        String type = storage.activeChannel();
        Path staging = TenantPaths.of(root).resolve(".staging");
        Path tmp = null;
        try {
            Files.createDirectories(staging);
            tmp = Files.createTempFile(staging, "content-", ".tmp");
            writer.write(tmp);
            long size = Files.size(tmp);
            storage.store(type).put(id, tmp, mimeOf(name));
            if (existing != null && !type.equals(existing.storage())) {
                storage.store(existing.storage()).delete(id); // a revision moves to where new files go now
            }
            WorkFile saved = existing != null
                    ? existing.withContent(name, WorkFileKind.of(name), mimeOf(name), size).withStorage(type)
                    : new WorkFile(id, name, WorkFileKind.of(name), mimeOf(name), size, type, id,
                    origin.owner(), origin.agentId(), origin.taskId(), origin.origin(), false, Instant.now(), Instant.now(),
                    List.of(), WorkFile.TAGS_PENDING);
            store.save(saved);
            if (existing == null && projects != null) {
                projects.recordFile(id, origin.agentId(), origin.taskId());
            }
            if (origin.messageId() != null) {
                store.link(id, origin.sessionId(), origin.messageId());
            }
            log.info("{} file {} ({}, {} bytes, {}) for {} via agent {} / task {}", existing != null ? "revised" : "new",
                    id, name, size, type, origin.owner(), origin.agentId(), origin.taskId());
            return saved;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store file " + name, e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** A run may overwrite only a file of its own: same agent, same task, or (agent-less) the same owner. */
    private WorkFile replaceable(String id, Origin origin) {
        WorkFile f = store.find(id).filter(x -> !x.deleted())
                .orElseThrow(() -> new IllegalArgumentException("没有这个文件：" + id + "（先用 file_list 查看）"));
        boolean mine = origin.agentId() != null ? origin.agentId().equals(f.agentId())
                : origin.taskId() != null ? origin.taskId().equals(f.taskId())
                : origin.owner() != null && origin.owner().equals(f.owner()) && f.agentId() == null;
        if (!mine) {
            throw new IllegalArgumentException("只能覆盖本 Agent / 本任务做出的文件：" + id);
        }
        if (WorkFile.ORIGIN_UPLOAD.equals(f.origin())) {
            throw new IllegalArgumentException("这是用户上传的文件，不能覆盖；请另存为新文件：" + id);
        }
        return f;
    }

    /** What a run made before (newest first), so the agent can pick one to revise. */
    public List<WorkFile> producedBy(Origin origin) {
        if (origin.agentId() != null) {
            return store.listFor(null, List.of(origin.agentId()));
        }
        return store.listFor(origin.owner(), List.of()).stream()
                .filter(f -> origin.taskId() != null ? origin.taskId().equals(f.taskId()) : f.agentId() == null)
                .toList();
    }

    // --- what people write themselves ---

    /** The largest Markdown file a person edits in the page. */
    public static final int EDIT_MAX_CHARS = 200_000;

    /** A Markdown file the user writes in the Files page: theirs alone, like anything they upload there. */
    public WorkFileDto createMarkdown(String user, String name, String content) {
        String clean = cleanName(name == null || name.isBlank() ? "未命名" : name);
        if (!clean.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            clean = clean + ".md";
        }
        WorkFile saved = writeText(new Origin(user, null, null, null, null, WorkFile.ORIGIN_UPLOAD), clean,
                limitedText(content), null);
        return get(saved.id(), user);
    }

    /** Rewrites a Markdown or plain-text file's content; whoever may rename or delete it may edit it. */
    public WorkFileDto editText(String id, String user, String content) {
        WorkFile f = manageable(id, user);
        if (WorkFile.LINK.equals(f.storage()) || !(f.name().toLowerCase(java.util.Locale.ROOT).endsWith(".md")
                || f.name().toLowerCase(java.util.Locale.ROOT).endsWith(".txt"))) {
            throw new IllegalArgumentException("只能在页面里编辑 Markdown 或纯文本文件");
        }
        byte[] bytes = limitedText(content).getBytes(StandardCharsets.UTF_8);
        store(new Origin(f.owner(), f.agentId(), f.taskId(), null, null, f.origin()), f.name(), f,
                target -> Files.write(target, bytes));
        return get(id, user);
    }

    private static String limitedText(String content) {
        String text = content == null ? "" : content;
        if (text.length() > EDIT_MAX_CHARS) {
            throw new IllegalArgumentException("内容太长了（最多 " + EDIT_MAX_CHARS + " 个字符）");
        }
        return text;
    }

    // --- what people attach to messages ---

    /**
     * Stores a file the user is about to send to an agent (or a task). Sending the same content to the same agent
     * again reuses the stored file instead of keeping a second copy.
     */
    public WorkFileDto upload(String user, String agentId, String taskId, String name, InputStream in) {
        Origin origin = uploadOrigin(user, agentId, taskId);
        String fileName = cleanName(name);
        Path staging = TenantPaths.of(root).resolve(".staging");
        Path tmp = null;
        try {
            Files.createDirectories(staging);
            tmp = Files.createTempFile(staging, "upload-", ".tmp");
            String hash = copyHashed(in, tmp);
            long size = Files.size(tmp);
            WorkFile same = sameUpload(origin, size, hash);
            if (same != null) {
                return get(same.id(), user);
            }
            Path source = tmp;
            WorkFile saved = store(origin, fileName, (String) null, target -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING));
            return get(saved.id(), user);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store file " + fileName, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a leftover staging file is harmless
                }
            }
        }
    }

    /** Links the files a message carries to it (their cards show under it) and hands back their content. */
    public List<Attachment> attach(List<String> fileIds, String user, String sessionId, String messageId) {
        List<String> ids = fileIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(fileIds));
        if (ids.size() > UPLOAD_MAX_FILES) {
            throw new IllegalArgumentException("一条消息最多附带 " + UPLOAD_MAX_FILES + " 个文件");
        }
        List<Attachment> out = new ArrayList<>();
        for (String id : ids) {
            WorkFile f = visible(id, user);
            if (WorkFile.LINK.equals(f.storage())) {
                throw new IllegalArgumentException("链接随消息文字发送即可：" + f.location());
            }
            store.link(id, sessionId, messageId);
            out.add(new Attachment(f.name(), f.mimeType(), localCopy(f)));
        }
        return out;
    }

    /**
     * Copies a message's attachments into {@code <workspace>/uploads/}: the agent reads them with its own tools,
     * whatever the format. Images the model can see are also handed over as images.
     */
    public static Delivered deliver(List<Attachment> files, String workspaceDir) {
        if (files.isEmpty()) {
            return new Delivered(List.of(), null);
        }
        if (workspaceDir == null) {
            throw new IllegalStateException("This conversation has no workspace to put the files in");
        }
        List<ImageAttachment> images = new ArrayList<>();
        List<String> placed = new ArrayList<>();
        Path dir = Path.of(workspaceDir).resolve(UPLOADS_DIR);
        try {
            if (Files.isSymbolicLink(dir)) {
                throw new IllegalStateException("The workspace's uploads folder is a link");
            }
            Files.createDirectories(dir);
            for (Attachment a : files) {
                Path target = freeTarget(dir, a.name());
                Files.copy(a.content(), target);
                placed.add(UPLOADS_DIR + "/" + target.getFileName());
                if (MODEL_IMAGES.contains(a.mimeType())) {
                    images.add(new ImageAttachment(a.content().toString(), a.mimeType()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot put the attachments into the workspace", e);
        }
        return new Delivered(images, "（用户随这条消息附带了文件，已放在你的工作目录：" + String.join("、", placed)
                + "。需要时用你的工具读取。）");
    }

    private Origin uploadOrigin(String user, String agentId, String taskId) {
        if (agentId != null && !agentId.isBlank()) {
            if (!canAccessAgent(agentId, user)) {
                throw new WorkFileNotFoundException(agentId);
            }
            return new Origin(user, agentId, null, null, null, WorkFile.ORIGIN_UPLOAD);
        }
        if (taskId != null && !taskId.isBlank()) {
            Task task = tasks.findTask(taskId).filter(t -> user != null && user.equals(t.createdBy()))
                    .orElseThrow(() -> new WorkFileNotFoundException(taskId));
            return new Origin(user, taskAgents.agentOf(task.id()).orElse(null), task.id(), null, null, WorkFile.ORIGIN_UPLOAD);
        }
        // Uploaded in the Files page: the user's own, for no agent or task in particular.
        return new Origin(user, null, null, null, null, WorkFile.ORIGIN_UPLOAD);
    }

    /** What was sent to the same agent before (or, without one, to the same task; without either, the user's own). */
    private List<WorkFile> sentTo(Origin origin) {
        if (origin.agentId() != null) {
            return store.listFor(null, List.of(origin.agentId()));
        }
        return store.listFor(origin.owner(), List.of()).stream()
                .filter(f -> origin.taskId() != null ? origin.taskId().equals(f.taskId()) : f.taskId() == null && f.agentId() == null)
                .toList();
    }

    /** An earlier upload with the same content, to the same agent (or, without one, the same task). */
    private WorkFile sameUpload(Origin origin, long size, String hash) throws IOException {
        for (WorkFile f : sentTo(origin)) {
            if (!f.deleted() && WorkFile.ORIGIN_UPLOAD.equals(f.origin()) && !WorkFile.LINK.equals(f.storage())
                    && f.size() == size) {
                try (InputStream in = openContent(f)) {
                    if (hash.equals(copyHashed(in, null))) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** Copies {@code in} to {@code target} (or just reads it, when null) within the upload limit; returns its SHA-256. */
    private static String copyHashed(InputStream in, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = target == null ? OutputStream.nullOutputStream() : Files.newOutputStream(target)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > UPLOAD_MAX_BYTES) {
                    throw new IllegalArgumentException("单个文件不能超过 " + UPLOAD_MAX_BYTES / 1024 / 1024 + " MB");
                }
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path freeTarget(Path dir, String name) {
        Path target = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; Files.exists(target); i++) {
            target = dir.resolve(base + " (" + i + ")" + ext);
        }
        return target;
    }

    private InputStream openContent(WorkFile f) throws IOException {
        try {
            return storage.store(f.storage()).open(f.location());
        } catch (java.nio.file.NoSuchFileException e) {
            throw new WorkFileNotFoundException(f.id());
        }
    }

    /**
     * The content as a file on this machine — for an agent's workspace or the model's image input. Local content is
     * used in place; content kept elsewhere is fetched once into a cache (a new version gets a new cache entry).
     */
    private Path localCopy(WorkFile f) {
        FileStore s = storage.store(f.storage());
        Optional<Path> local = s.localPath(f.location());
        if (local.isPresent()) {
            return local.get();
        }
        Path cached = TenantPaths.of(root).resolve(".cache").resolve(f.id() + "-" + f.updatedAt().toEpochMilli());
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        try {
            Files.createDirectories(cached.getParent());
            Path tmp = Files.createTempFile(cached.getParent(), "fetch-", ".tmp");
            try (InputStream in = openContent(f)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                deleteQuietly(tmp);
            }
            return cached;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot fetch file " + f.id(), e);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // a stray staging file is harmless
        }
    }

    // --- links people share in a message ---

    /**
     * Keeps the web addresses in a message the user sends to an agent (or a task) as link files under it, linked to
     * the message. A link already kept for that agent is linked again rather than kept twice. Nothing is fetched:
     * many links (intranet docs) need the user's own login anyway, and the agent reads them with its own tools.
     */
    public List<WorkFile> saveLinks(String user, String agentId, String taskId, String sessionId, String messageId,
                                    String text) {
        List<String> urls = Links.find(text);
        if (urls.isEmpty() || user == null) {
            return List.of();
        }
        Origin origin = new Origin(user, agentId, taskId, sessionId, messageId, WorkFile.ORIGIN_UPLOAD);
        List<WorkFile> known = sentTo(origin).stream().filter(f -> WorkFile.LINK.equals(f.storage())).toList();
        List<WorkFile> out = new ArrayList<>();
        for (String url : urls) {
            WorkFile f = known.stream().filter(k -> url.equals(k.location())).findFirst().orElse(null);
            if (f == null) {
                Instant now = Instant.now();
                f = new WorkFile(UUID.randomUUID().toString(), Links.name(url), WorkFileKind.LINK, "text/uri-list", 0,
                        WorkFile.LINK, url, user, agentId, taskId, WorkFile.ORIGIN_UPLOAD, false, now, now, List.of(),
                        WorkFile.TAGS_PENDING);
                store.save(f);
                if (projects != null) {
                    projects.recordFile(f.id(), agentId, taskId);
                }
            }
            if (messageId != null) {
                store.link(f.id(), sessionId, messageId);
            }
            out.add(f);
        }
        return out;
    }

    // --- people using files ---

    /** The files made in a project, for one of its members (none for anyone else). */
    public List<WorkFileDto> listForProject(String user, String projectId) {
        if (projects == null) {
            return List.of();
        }
        List<WorkFile> files = projects.itemsInProject(projectId, com.superagent.core.project.ProjectItemKind.FILE, user).stream()
                .map(store::find).flatMap(java.util.Optional::stream).filter(f -> !f.deleted()).toList();
        Map<String, List<String>> refs = store.messages(files.stream().map(WorkFile::id).toList());
        Map<String, String> placed = folders.placements(user);
        return files.stream().map(f -> toDto(f, user, refs.getOrDefault(f.id(), List.of()), placed)).toList();
    }

    /** Files the user can see; {@code agentId} narrows to one agent's (which the user must have access to). */
    public List<WorkFileDto> list(String user, String agentId) {
        List<WorkFile> files;
        if (agentId != null && !agentId.isBlank()) {
            if (!canAccessAgent(agentId, user)) {
                throw new WorkFileNotFoundException(agentId);
            }
            files = store.listFor(null, List.of(agentId));
        } else {
            files = store.listFor(user, accessibleAgents(user));
        }
        Map<String, List<String>> refs = store.messages(files.stream().map(WorkFile::id).toList());
        Map<String, String> placed = folders.placements(user);
        return files.stream().map(f -> toDto(f, user, refs.getOrDefault(f.id(), List.of()), placed)).toList();
    }

    /** Copies a file the user can see into a run's workspace ({@code uploads/}), for it to work on; returns the relative path. */
    public String copyToWorkspace(String id, String user, Path workspace) {
        WorkFile f = visible(id, user);
        if (WorkFile.LINK.equals(f.storage())) {
            throw new IllegalArgumentException("这是一个链接，没有文件内容：" + f.location());
        }
        try {
            Path dir = workspace.resolve(UPLOADS_DIR);
            Files.createDirectories(dir);
            Path target = freeTarget(dir, f.name());
            Files.copy(localCopy(f), target);
            return UPLOADS_DIR + "/" + target.getFileName();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot copy file " + id, e);
        }
    }

    /** The start of a text file the user can see, a link's address, or null for what has no text to read. */
    public String readText(String id, String user, int maxChars) {
        WorkFile f = visible(id, user);
        if (WorkFile.LINK.equals(f.storage())) {
            return f.location();
        }
        if (f.kind() != WorkFileKind.DOCUMENT || !(f.mimeType().startsWith("text/")
                || f.name().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt|csv|json|html?|xml|ya?ml)$"))) {
            return null;
        }
        try (InputStream in = openContent(f)) {
            String text = new String(in.readNBytes(maxChars * 4), StandardCharsets.UTF_8);
            return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n…（后面还有内容，已截断）";
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file " + id, e);
        }
    }

    /**
     * What a task produced (the files its agent saved), as far as {@code user} can see. Its final reply is its output,
     * shown with the task, not a file.
     */
    public List<WorkFileDto> listForTask(String user, String taskId) {
        return list(user, null).stream().filter(f -> taskId.equals(f.taskId())).toList();
    }

    public WorkFileDto get(String id, String user) {
        WorkFile f = visible(id, user);
        return toDto(f, user, store.messages(List.of(id)).getOrDefault(id, List.of()));
    }

    public Content content(String id, String user) {
        WorkFile f = visible(id, user);
        if (WorkFile.LINK.equals(f.storage())) {
            throw new IllegalArgumentException("这是一个链接，没有可下载的内容：" + f.location());
        }
        FileStore s = storage.store(f.storage());
        Resource resource = s.localPath(f.location()).<Resource>map(FileSystemResource::new)
                .orElseGet(() -> new StoredResource(s, f));
        return new Content(resource, f.name(), f.mimeType());
    }

    public WorkFileDto rename(String id, String user, String name) {
        WorkFile f = manageable(id, user);
        String clean = cleanName(name);
        boolean link = WorkFile.LINK.equals(f.storage()); // a link stays a link whatever it's called
        store.save(f.withContent(clean, link ? f.kind() : WorkFileKind.of(clean), link ? f.mimeType() : mimeOf(clean),
                f.size()));
        return get(id, user);
    }

    /** The file drops out of every list and its content is removed from its store (a link has none). */
    public void delete(String id, String user) {
        WorkFile f = manageable(id, user);
        if (!WorkFile.LINK.equals(f.storage())) {
            try {
                storage.store(f.storage()).delete(f.location());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot delete the content of file " + id, e);
            }
        }
        store.save(f.markDeleted());
    }

    /** Content kept in a remote store, served through us: each read opens a fresh stream. */
    private static final class StoredResource extends AbstractResource {

        private final FileStore store;
        private final WorkFile file;

        StoredResource(FileStore store, WorkFile file) {
            this.store = store;
            this.file = file;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return store.open(file.location());
        }

        @Override
        public long contentLength() {
            return file.size();
        }

        @Override
        public String getFilename() {
            return file.name();
        }

        @Override
        public String getDescription() {
            return "file " + file.id() + " in the " + file.storage() + " store";
        }
    }

    private com.superagent.core.project.ProjectAccess projects; // null in tests: no projects

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProjects(com.superagent.core.project.ProjectAccess projects) {
        this.projects = projects;
    }

    private WorkFile visible(String id, String user) {
        WorkFile f = store.find(id).filter(x -> !x.deleted()).orElseThrow(() -> new WorkFileNotFoundException(id));
        boolean ok = user != null && (user.equals(f.owner()) || (f.agentId() != null && canAccessAgent(f.agentId(), user))
                || (projects != null && projects.visibleThroughProject(com.superagent.core.project.ProjectItemKind.FILE, id, user)));
        if (!ok) {
            throw new WorkFileNotFoundException(id);
        }
        return f;
    }

    private WorkFile manageable(String id, String user) {
        WorkFile f = visible(id, user);
        if (!canManage(f, user)) {
            throw new WorkFileForbiddenException("只有发起人或 Agent 的所有者能修改这个文件");
        }
        return f;
    }

    private boolean canManage(WorkFile f, String user) {
        return user != null && (user.equals(f.owner())
                || (f.agentId() != null && agents.find(f.agentId()).map(Agent::owner).filter(user::equals).isPresent()));
    }

    private boolean canAccessAgent(String agentId, String user) {
        if (user == null) {
            return false;
        }
        return agents.find(agentId).filter(a -> a.status() != AgentStatus.DELETED)
                .filter(a -> user.equals(a.owner()) || members.members(a.id()).stream().anyMatch(m -> m.username().equals(user))
                        || (projects != null && projects.sharesProject(a.id(), user)))
                .isPresent();
    }

    private List<String> accessibleAgents(String user) {
        Set<String> ids = new LinkedHashSet<>();
        agents.list().stream().filter(a -> a.status() != AgentStatus.DELETED && user.equals(a.owner()))
                .forEach(a -> ids.add(a.id()));
        ids.addAll(members.agentsOf(user));
        if (projects != null) {
            ids.addAll(projects.agentsThroughProjects(user));
        }
        return new ArrayList<>(ids);
    }

    private WorkFileDto toDto(WorkFile f, String user, List<String> messageIds) {
        return toDto(f, user, messageIds, folders.placements(user));
    }

    private WorkFileDto toDto(WorkFile f, String user, List<String> messageIds, Map<String, String> placed) {
        String folderId = placed.get(f.id());
        Agent agent = f.agentId() == null ? null : agents.find(f.agentId()).orElse(null);
        Task task = f.taskId() == null ? null : tasks.findTask(f.taskId()).orElse(null);
        return new WorkFileDto(f.id(), f.name(), f.kind().name().toLowerCase(Locale.ROOT), f.mimeType(), f.size(),
                f.storage(), f.origin(), f.owner(), displayName(f.owner()), f.agentId(),
                agent == null ? null : agent.name(), agent == null ? null : agent.avatar(), f.taskId(),
                task == null ? null : task.title(), messageIds, canManage(f, user), f.createdAt(), f.updatedAt(),
                f.tags(), f.tagState(), WorkFile.LINK.equals(f.storage()) ? f.location() : null,
                folderId == null || folderId.isEmpty() ? null : folderId);
    }

    private String displayName(String userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId).map(u -> u.displayName() != null && !u.displayName().isBlank() ? u.displayName()
                : u.username()).orElse(userId);
    }

    /** A plain file name: no folders (a file has none), no control characters, a sane length. */
    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        n = n.replaceAll("^\\.+", "");
        if (n.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        return n.length() <= 200 ? n : n.substring(0, 200);
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".md") || n.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".webm")) {
            return "video/webm";
        }
        if (n.endsWith(".mov")) {
            return "video/quicktime";
        }
        String guessed = URLConnection.guessContentTypeFromName(n);
        return guessed == null ? "application/octet-stream" : guessed;
    }
}
