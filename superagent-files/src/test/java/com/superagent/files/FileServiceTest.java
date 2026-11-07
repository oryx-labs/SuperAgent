package com.superagent.files;

import com.superagent.api.file.WorkFileDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMember;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.file.FileStore;
import com.superagent.core.file.FileStoreProvider;
import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileKind;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.security.SecretCipher;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskStore;
import com.superagent.core.user.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileServiceTest {

    private static final class InMemoryFiles implements WorkFileStore {
        final Map<String, WorkFile> rows = new LinkedHashMap<>();
        final Map<String, Set<String>> refs = new LinkedHashMap<>();
        @Override public void save(WorkFile f) { rows.put(f.id(), f); }
        @Override public Optional<WorkFile> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public List<WorkFile> listFor(String owner, Collection<String> agentIds) {
            return rows.values().stream().filter(f -> !f.deleted())
                    .filter(f -> (owner != null && owner.equals(f.owner())) || (f.agentId() != null && agentIds.contains(f.agentId())))
                    .sorted(Comparator.comparing(WorkFile::updatedAt).reversed()).toList();
        }
        @Override public List<WorkFile> untagged(int limit) {
            return rows.values().stream().filter(f -> !f.deleted() && f.tagState().isEmpty()).limit(limit).toList();
        }
        @Override public void link(String fileId, String sessionId, String messageId) {
            refs.computeIfAbsent(fileId, k -> new LinkedHashSet<>()).add(messageId);
        }
        @Override public Map<String, Long> countByStorage() {
            Map<String, Long> out = new LinkedHashMap<>();
            rows.values().stream().filter(f -> !f.deleted()).forEach(f -> out.merge(f.storage(), 1L, Long::sum));
            return out;
        }
        @Override public Map<String, List<String>> messages(Collection<String> fileIds) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            fileIds.stream().filter(refs::containsKey).forEach(id -> out.put(id, new ArrayList<>(refs.get(id))));
            return out;
        }
    }

    @TempDir
    Path tmp;

    private static Agent agent(String id, String owner) {
        return new Agent(id, id, "🙂", null, null, null, AgentStatus.ACTIVE, owner, AgentKind.STANDARD, null,
                Instant.now(), Instant.now());
    }

    /** ada owns agent "dev" and shares it with bob; carl is an outsider; task t1 was set off by "dev". */
    private FileService service(InMemoryFiles store) {
        AgentStore agents = mock(AgentStore.class);
        when(agents.find("dev")).thenReturn(Optional.of(agent("dev", "ada")));
        when(agents.find("ops")).thenReturn(Optional.of(agent("ops", "carl")));
        when(agents.list()).thenReturn(List.of(agent("dev", "ada"), agent("ops", "carl")));
        AgentMemberStore members = mock(AgentMemberStore.class);
        when(members.members("dev")).thenReturn(List.of(new AgentMember("dev", "bob", "ada", true, Instant.now(), Instant.now())));
        when(members.agentsOf("bob")).thenReturn(List.of("dev"));
        TaskAgentStore taskAgents = mock(TaskAgentStore.class);
        when(taskAgents.agentOf(anyString())).thenAnswer(i -> "t1".equals(i.getArgument(0)) ? Optional.of("dev") : Optional.empty());
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.findTask(any())).thenReturn(Optional.empty());
        UserStore users = mock(UserStore.class);
        when(users.findByUsername(any())).thenReturn(Optional.empty());
        return new FileService(store, agents, members, tasks, taskAgents, users, localStorage(store), folderStore,
                tmp.resolve("files").toString(), 1);
    }

    private final Map<String, String> settingRows = new LinkedHashMap<>();
    private final InMemoryFolders folderStore = new InMemoryFolders();

    private static final class InMemoryFolders implements com.superagent.core.file.FileFolderStore {
        final Map<String, com.superagent.core.file.FileFolder> rows = new LinkedHashMap<>();
        final Map<String, Map<String, String>> placed = new HashMap<>();
        @Override public void save(com.superagent.core.file.FileFolder f) { rows.put(f.id(), f); }
        @Override public Optional<com.superagent.core.file.FileFolder> find(String id) {
            return Optional.ofNullable(rows.get(id)).filter(f -> !f.deleted()); }
        @Override public List<com.superagent.core.file.FileFolder> list(String owner) {
            return rows.values().stream().filter(f -> !f.deleted() && f.owner().equals(owner)).toList(); }
        @Override public List<String> owners() {
            return rows.values().stream().filter(f -> !f.deleted()).map(com.superagent.core.file.FileFolder::owner).distinct().toList(); }
        @Override public Map<String, String> placements(String owner) { return new HashMap<>(placed.getOrDefault(owner, Map.of())); }
        @Override public void place(String owner, String fileId, String folderId, String by) {
            placed.computeIfAbsent(owner, k -> new HashMap<>()).put(fileId, folderId); }
    }

    private FileStorageService storage;
    /** An in-memory "cloud" store, to see files go there once it's chosen; keys are bucket/id. */
    private final Map<String, byte[]> cloud = new LinkedHashMap<>();

    /** Settings say nothing: new files go to the local disk until the "mem" store is chosen. */
    private FileStorageService localStorage(WorkFileStore files) {
        SystemSettingStore settings = new SystemSettingStore() {
            @Override public Map<String, String> all() { return new LinkedHashMap<>(settingRows); }
            @Override public void put(String key, String value) { settingRows.put(key, value); }
        };
        FileStoreProvider mem = new FileStoreProvider() {
            @Override public String type() { return "mem"; }
            @Override public String label() { return "memory"; }
            @Override public List<Field> fields() { return List.of(new Field("bucket", "Bucket", false, true, "", true),
                    new Field("secret", "Secret", true, true, "", false)); }
            @Override public FileStore open(Map<String, String> config) {
                String bucket = config.get("bucket") + "/";
                return new FileStore() {
                    @Override public void put(String id, Path source, String type) throws java.io.IOException {
                        cloud.put(bucket + id, Files.readAllBytes(source)); }
                    @Override public java.io.InputStream open(String id) throws java.io.IOException {
                        byte[] b = cloud.get(bucket + id);
                        if (b == null) throw new java.nio.file.NoSuchFileException(id);
                        return new java.io.ByteArrayInputStream(b); }
                    @Override public void delete(String id) { cloud.remove(bucket + id); }
                };
            }
        };
        storage = new FileStorageService(List.of(new LocalFileStoreProvider(tmp.resolve("files").toString()), mem), settings,
                new SecretCipher("", tmp.resolve("secret.key").toString()), files);
        return storage;
    }

    private static FileService.Origin chat(String owner, String agent, String message) {
        return new FileService.Origin(owner, agent, null, "s1", message, WorkFile.ORIGIN_CHAT);
    }

    @Test
    void anAgentsFilesAreSharedWithItsMembersAndRevisedInPlace() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        WorkFile draft = svc.writeText(chat("bob", "dev", "m1"), "第01节/讲义.md", "初稿", null);
        assertThat(draft.name()).isEqualTo("第01节_讲义.md"); // a file has no folders

        WorkFile revised = svc.writeText(chat("bob", "dev", "m2"), "第01节讲义.md", "二稿", draft.id());
        assertThat(revised.id()).isEqualTo(draft.id());
        assertThat(new String(svc.content(draft.id(), "ada").resource().getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("二稿"); // latest only
        assertThatThrownBy(() -> svc.writeText(chat("carl", "ops", "m3"), "x.md", "覆盖", draft.id()))
                .isInstanceOf(IllegalArgumentException.class); // another agent's file

        // bob gave the instruction, ada owns the agent: both see it, both replies show its card; carl doesn't.
        assertThat(svc.list("ada", null)).extracting(WorkFileDto::id).containsExactly(draft.id());
        assertThat(svc.list("bob", "dev").get(0).messageIds()).containsExactly("m1", "m2");
        assertThat(svc.list("carl", null)).isEmpty();
        assertThatThrownBy(() -> svc.get(draft.id(), "carl")).isInstanceOf(WorkFileNotFoundException.class);

        // A plain member who isn't the instruction's giver can't delete; the agent's owner can.
        WorkFile adas = svc.writeText(chat("ada", "dev", "m4"), "提纲.md", "提纲", null);
        assertThat(svc.get(adas.id(), "bob").canManage()).isFalse();
        assertThatThrownBy(() -> svc.delete(adas.id(), "bob")).isInstanceOf(WorkFileForbiddenException.class);
        svc.delete(draft.id(), "ada");
        assertThat(svc.list("bob", null)).extracting(WorkFileDto::id).containsExactly(adas.id());
    }

    @Test
    void uploadsLandUnderTheAgentDedupeAndAreDeliveredIntoTheWorkspace() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        byte[] doc = "需求文档".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // bob (a member of "dev") uploads; the same content again reuses the stored file.
        WorkFileDto first = svc.upload("bob", "dev", null, "需求.docx", new java.io.ByteArrayInputStream(doc));
        WorkFileDto again = svc.upload("bob", "dev", null, "副本.docx", new java.io.ByteArrayInputStream(doc));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.origin()).isEqualTo(WorkFile.ORIGIN_UPLOAD);
        assertThat(svc.list("ada", "dev")).extracting(WorkFileDto::id).contains(first.id()); // the agent's owner sees it
        assertThatThrownBy(() -> svc.upload("carl", "dev", null, "x.txt", new java.io.ByteArrayInputStream(doc)))
                .isInstanceOf(WorkFileNotFoundException.class);

        // Sending links it to the message and copies it into the workspace for the agent to read itself.
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        WorkFileDto image = svc.upload("bob", "dev", null, "图.png", new java.io.ByteArrayInputStream(png));
        FileService.Delivered d = FileService.deliver(svc.attach(List.of(first.id(), image.id()), "bob", "s1", "m1"), ws.toString());
        assertThat(ws.resolve("uploads/需求.docx")).hasBinaryContent(doc);
        assertThat(d.images()).hasSize(1); // the image also goes to the model as an image
        assertThat(d.note()).contains("uploads/需求.docx", "uploads/图.png");
        assertThat(svc.get(first.id(), "bob").messageIds()).containsExactly("m1");
        assertThatThrownBy(() -> svc.attach(List.of(first.id()), "carl", "s1", "m2")).isInstanceOf(WorkFileNotFoundException.class);
    }

    @Test
    void peopleWriteMarkdownAndUploadAnyFileInTheFilesPageAndEditTheirText() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);

        WorkFileDto note = svc.createMarkdown("bob", "周会纪要", "# 周会\n- 上线");
        assertThat(note.name()).isEqualTo("周会纪要.md");
        assertThat(note.agentId()).isNull();
        assertThat(new String(svc.content(note.id(), "bob").resource().getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("# 周会\n- 上线");
        assertThat(svc.editText(note.id(), "bob", "# 周会\n- 已上线").size()).isEqualTo("# 周会\n- 已上线".getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length);
        assertThatThrownBy(() -> svc.editText(note.id(), "carl", "x")).isInstanceOf(WorkFileNotFoundException.class);

        // Any file, for no agent or task: the same content twice is kept once; only text is edited in the page.
        byte[] xlsx = {1, 2, 3, 4};
        WorkFileDto sheet = svc.upload("bob", null, null, "预算.xlsx", new java.io.ByteArrayInputStream(xlsx));
        assertThat(svc.upload("bob", null, null, "预算2.xlsx", new java.io.ByteArrayInputStream(xlsx)).id()).isEqualTo(sheet.id());
        assertThat(svc.list("bob", null)).extracting(WorkFileDto::id).contains(note.id(), sheet.id());
        assertThat(svc.list("carl", null)).isEmpty();
        assertThatThrownBy(() -> svc.editText(sheet.id(), "bob", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentTagsAFileAPersonsTagsWinAndTheTaggerOnlyFillsUntaggedOnes() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        WorkFile lesson = svc.writeText(chat("bob", "dev", "m1"), "讲义.md", "第一节", null, List.of("课程", "#讲义", "课程"));
        assertThat(lesson.tags()).containsExactly("课程", "讲义");
        assertThat(svc.tagsInUse("ada", null)).contains("课程", "讲义");

        svc.setTags(lesson.id(), "ada", List.of("行动营"));
        svc.writeText(chat("bob", "dev", "m2"), "讲义.md", "第一节·二稿", lesson.id(), List.of("草稿")); // agent revises
        assertThat(store.rows.get(lesson.id()).tags()).containsExactly("行动营"); // the person's tags stay

        WorkFile plain = svc.writeText(chat("bob", "dev", "m3"), "周报.md", "本周进展：完成讲义", null);
        assertThat(svc.untagged(10)).singleElement().satisfies(u -> {
            assertThat(u.file().id()).isEqualTo(plain.id());
            assertThat(u.excerpt()).contains("本周进展");
            assertThat(u.vocabulary()).contains("行动营");
        });
        svc.autoTag(plain.id(), List.of("周报"));
        svc.autoTag(lesson.id(), List.of("不该覆盖"));
        assertThat(store.rows.get(plain.id()).tags()).containsExactly("周报");
        assertThat(store.rows.get(lesson.id()).tags()).containsExactly("行动营");
        assertThat(svc.untagged(10)).isEmpty();
    }

    @Test
    void linksInAMessageBecomeLinkFilesOncePerAgent() {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        String text = "看下 https://github.com/acme/repo/pull/12。还有（https://example.com/a?b=1），以及 http://x";
        List<WorkFile> saved = svc.saveLinks("bob", "dev", null, "s1", "m1", text);
        assertThat(saved).extracting(WorkFile::location)
                .containsExactly("https://github.com/acme/repo/pull/12", "https://example.com/a?b=1", "http://x");
        assertThat(saved.get(0).name()).isEqualTo("github.com/acme/repo/pull/12");
        assertThat(saved).allSatisfy(f -> assertThat(f.kind()).isEqualTo(WorkFileKind.LINK));

        // The same link sent again is linked to the new message, not kept twice.
        svc.saveLinks("bob", "dev", null, "s1", "m2", "再看 https://github.com/acme/repo/pull/12");
        WorkFileDto link = svc.list("ada", "dev").stream().filter(f -> "link".equals(f.kind()))
                .filter(f -> f.url().endsWith("/pull/12")).findFirst().orElseThrow();
        assertThat(svc.list("ada", "dev")).filteredOn(f -> "link".equals(f.kind())).hasSize(3);
        assertThat(link.messageIds()).containsExactly("m1", "m2");
        assertThatThrownBy(() -> svc.content(link.id(), "bob")).isInstanceOf(IllegalArgumentException.class);
        assertThat(svc.rename(link.id(), "bob", "PR 12").kind()).isEqualTo("link"); // stays a link
    }

    @Test
    void filesRememberTheirChannelSoMovingTheStorageLosesNothing() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        WorkFile local = svc.writeText(chat("bob", "dev", "m1"), "旧.md", "本地内容", null);
        assertThat(local.storage()).isEqualTo(WorkFile.INTERNAL);

        // The admin picks the "mem" store: the settings are checked (a probe round-trip) and the secret kept encrypted.
        assertThatThrownBy(() -> storage.save("mem", Map.of("bucket", "a"))).hasMessageContaining("Secret");
        String channelA = storage.save("mem", Map.of("bucket", "a", "secret", "s3cr3t")).active();
        assertThat(settingRows.get("files.storage.channel." + channelA + ".secret")).startsWith("v1:").doesNotContain("s3cr3t");
        assertThat(storage.view().providers()).filteredOn(p -> p.type().equals("mem")).singleElement()
                .satisfies(p -> assertThat(p.fields()).filteredOn(f -> f.secret()).singleElement()
                        .satisfies(f -> { assertThat(f.value()).isNull(); assertThat(f.set()).isTrue(); }));
        assertThat(cloud).isEmpty(); // the probe cleaned up after itself
        WorkFile inA = svc.writeText(chat("bob", "dev", "m2"), "a.md", "桶 A", null);
        assertThat(inA.storage()).isEqualTo(channelA);
        assertThat(cloud).containsKey("a/" + inA.id());

        // A rotated key is the same place: same channel, and a blank secret keeps the saved one.
        assertThat(storage.save("mem", Map.of("bucket", "a", "secret", "rotated")).active()).isEqualTo(channelA);
        assertThat(storage.save("mem", Map.of("bucket", "a")).active()).isEqualTo(channelA);

        // Another bucket is another channel: new files go there, earlier ones are still read from where they are.
        var moved = storage.save("mem", Map.of("bucket", "b"));
        assertThat(moved.active()).isNotEqualTo(channelA);
        WorkFile inB = svc.writeText(chat("bob", "dev", "m3"), "b.md", "桶 B", null);
        assertThat(inB.storage()).isEqualTo(moved.active());
        assertThat(cloud).containsKey("b/" + inB.id());
        assertThat(read(svc, local.id())).isEqualTo("本地内容");
        assertThat(read(svc, inA.id())).isEqualTo("桶 A");
        assertThat(read(svc, inB.id())).isEqualTo("桶 B");
        assertThat(storage.view().channels()).extracting(c -> c.id() + ":" + c.files() + ":" + c.active()).containsExactly(
                "internal:1:false", channelA + ":1:false", moved.active() + ":1:true");

        // Going back to a place reuses its channel; deleting removes content from the file's own channel.
        assertThat(storage.save("mem", Map.of("bucket", "a")).active()).isEqualTo(channelA);
        svc.delete(inB.id(), "bob");
        assertThat(cloud).doesNotContainKey("b/" + inB.id());
    }

    @Test
    void settingsSavedBeforeChannelsBecomeAChannelFilesStillFind() throws Exception {
        settingRows.put("files.storage.type", "mem");
        settingRows.put("files.storage.mem.bucket", "old");
        settingRows.put("files.storage.mem.secret", new SecretCipher("", tmp.resolve("secret.key").toString()).encrypt("s"));
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        assertThat(storage.activeChannel()).isEqualTo("mem"); // existing files say storage = "mem"
        WorkFile f = svc.writeText(chat("bob", "dev", "m1"), "x.md", "老配置", null);
        assertThat(f.storage()).isEqualTo("mem");
        assertThat(cloud).containsKey("old/" + f.id());
        assertThat(read(svc, f.id())).isEqualTo("老配置");
    }

    @Test
    void foldersArePerPersonAndNeverCostAFile() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        FileFolderService folders = new FileFolderService(folderStore, svc);
        var project = folders.create("ada", "项目", null);
        var q4 = folders.create("ada", "Q4", project.id());
        assertThat(q4.path()).isEqualTo("项目/Q4");
        var deep = folders.create("ada", "第三层", q4.id());
        assertThatThrownBy(() -> folders.create("ada", "第四层", deep.id())).hasMessageContaining("3 层");
        assertThatThrownBy(() -> folders.create("ada", "项目", null)).hasMessageContaining("已经有");
        assertThatThrownBy(() -> folders.create("ada", "a/b", null)).hasMessageContaining("/");
        assertThatThrownBy(() -> folders.rename("bob", q4.id(), "偷看")).hasMessageContaining("没有这个目录");

        // Bob made it; Ada sees it through the agent and keeps it in her own tree, without moving it for Bob.
        WorkFile f = svc.writeText(chat("bob", "dev", "m1"), "报告.md", "内容", null);
        folders.move("ada", f.id(), q4.id());
        assertThat(svc.get(f.id(), "ada").folderId()).isEqualTo(q4.id());
        assertThat(svc.get(f.id(), "bob").folderId()).isNull();
        assertThatThrownBy(() -> folders.move("ada", f.id(), "nope")).hasMessageContaining("没有这个目录");

        // Deleting a folder takes nothing with it: files and subfolders go up a level.
        folders.delete("ada", q4.id());
        assertThat(svc.get(f.id(), "ada").folderId()).isEqualTo(project.id());
        assertThat(folders.paths("ada")).containsExactly("项目", "项目/第三层");
        folders.delete("ada", project.id());
        assertThat(svc.get(f.id(), "ada").folderId()).isNull();
        assertThat(read(svc, f.id())).isEqualTo("内容");
    }

    @Test
    void anAgentNamesAnExistingFolderAndTheAutoFilerOnlySortsWhatsNew() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        FileFolderService folders = new FileFolderService(folderStore, svc);
        WorkFile before = svc.writeText(chat("bob", "dev", "m0"), "早就有.md", "x", null);
        Thread.sleep(5);
        assertThat(folders.unfiled("bob", 10)).isEmpty(); // no folders: nothing to ask about

        assertThat(folders.createPath("bob", "项目/ Q4 ")).isEqualTo("项目/Q4");
        assertThat(folders.createPath("bob", "项目/Q4")).isEqualTo("项目/Q4"); // already there
        Thread.sleep(5);
        WorkFile named = svc.writeText(chat("bob", "dev", "m1"), "a.md", "x", null);
        assertThat(folders.placeByAgent("bob", named.id(), "项目/Q4")).isTrue();
        assertThat(folders.placeByAgent("bob", named.id(), "没这个目录")).isFalse();

        WorkFile fresh = svc.writeText(chat("bob", "dev", "m2"), "b.md", "x", null);
        WorkFile unsure = svc.writeText(chat("bob", "dev", "m3"), "c.md", "x", null);
        assertThat(folders.unfiled("bob", 10)).extracting(u -> u.file().id())
                .containsExactlyInAnyOrder(fresh.id(), unsure.id()).doesNotContain(before.id(), named.id());

        folders.autoFile("bob", fresh.id(), "项目/Q4");
        folders.autoFile("bob", unsure.id(), "模型编的目录"); // not a folder: unfiled, and not asked about again
        assertThat(svc.get(fresh.id(), "bob").folderId()).isNotNull();
        assertThat(svc.get(unsure.id(), "bob").folderId()).isNull();
        assertThat(folders.unfiled("bob", 10)).isEmpty();

        folders.move("bob", fresh.id(), null); // a person's move stands: a late answer doesn't undo it
        folders.autoFile("bob", fresh.id(), "项目/Q4");
        assertThat(svc.get(fresh.id(), "bob").folderId()).isNull();
    }

    @Test
    void conversationsKeepFilesInOrderAndTheSuperAgentReachesAsFarAsItsUserOnly() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        FileFolderService folders = new FileFolderService(folderStore, svc);
        FileFolderMcpTools tidy = new FileFolderMcpTools(svc, folders);
        FileManageMcpTools manage = new FileManageMcpTools(svc);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var chat = new com.superagent.runtime.mcp.McpGrants.Grant("t", com.superagent.runtime.mcp.McpGrants.Kind.CHAT, "bob", null, "dev", "s", "m", null);
        var sa = new com.superagent.runtime.mcp.McpGrants.Grant("t", com.superagent.runtime.mcp.McpGrants.Kind.SUPER, "bob", null, "sa", "s", "m", null);
        var background = new com.superagent.runtime.mcp.McpGrants.Grant("t", com.superagent.runtime.mcp.McpGrants.Kind.SUPER, "bob", null, "sa", null, null, null);
        WorkFile f = svc.writeText(chat("bob", "dev", "m1"), "竞品分析.md", "正文内容", null);

        tidy.call(chat, "file_create_folder", json.readTree("{\"path\":\"项目/Q4\"}"));
        tidy.call(chat, "file_create_folder", json.readTree("{\"path\":\"调研\"}"));
        tidy.call(chat, "file_move", json.readTree("{\"file_id\":\"" + f.id() + "\",\"folder\":\"项目/Q4\"}"));
        assertThat(tidy.call(chat, "file_find", json.readTree("{\"query\":\"竞品\",\"folder\":\"项目\"}")).toString()).contains(f.id());
        assertThat(tidy.call(chat, "file_folders", json.readTree("{}")).toString()).contains("项目/Q4（1 个文件）");

        // A folder moves with what's in it, within the limits.
        tidy.call(chat, "file_move_folder", json.readTree("{\"path\":\"项目/Q4\",\"parent\":\"调研\"}"));
        assertThat(folders.paths("bob")).contains("调研/Q4");
        assertThatThrownBy(() -> tidy.call(chat, "file_move_folder", json.readTree("{\"path\":\"调研\",\"parent\":\"调研/Q4\"}")))
                .hasMessageContaining("它自己里面");

        // The SuperAgent's chat works on the file; its background rounds touch nothing.
        assertThat(manage.call(sa, "file_read", json.readTree("{\"file_id\":\"" + f.id() + "\"}")).toString()).contains("正文内容");
        assertThatThrownBy(() -> manage.call(background, "file_delete", json.readTree("{\"file_id\":\"" + f.id() + "\"}")))
                .hasMessageContaining("聊天");
        assertThatThrownBy(() -> tidy.call(background, "file_move", json.readTree("{\"file_id\":\"" + f.id() + "\"}")))
                .hasMessageContaining("聊天");
        // Bob can only view what Ada made through her agent: his SuperAgent can read it, but not delete it for him.
        WorkFile hers = svc.writeText(chat("ada", "dev", "m9"), "她的.md", "她的内容", null);
        assertThat(manage.call(sa, "file_read", json.readTree("{\"file_id\":\"" + hers.id() + "\"}")).toString()).contains("她的内容");
        assertThatThrownBy(() -> manage.call(sa, "file_delete", json.readTree("{\"file_id\":\"" + hers.id() + "\"}")))
                .isInstanceOf(com.superagent.runtime.mcp.McpToolException.class);
        manage.call(sa, "file_delete", json.readTree("{\"file_id\":\"" + f.id() + "\"}"));
        assertThat(store.find(f.id())).hasValueSatisfying(w -> assertThat(w.deleted()).isTrue());
    }

    @Test
    void aMemberFetchesAFileItWasPointedAtIntoItsWorkspace() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        WorkFile f = svc.writeText(chat("bob", "dev", "m1"), "风格.md", "参考内容", null);
        Path workspace = tmp.resolve("ws");
        assertThat(svc.copyToWorkspace(f.id(), "ada", workspace)).isEqualTo("uploads/风格.md");
        assertThat(Files.readString(workspace.resolve("uploads/风格.md"))).isEqualTo("参考内容");
        assertThat(svc.copyToWorkspace(f.id(), "ada", workspace)).isNotEqualTo("uploads/风格.md"); // never overwrites
        assertThatThrownBy(() -> svc.copyToWorkspace(f.id(), "carl", workspace))
                .isInstanceOf(com.superagent.core.error.NotFoundException.class); // can't see it, can't fetch it
    }

    private static String read(FileService svc, String id) throws Exception {
        try (var in = svc.content(id, "ada").resource().getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
