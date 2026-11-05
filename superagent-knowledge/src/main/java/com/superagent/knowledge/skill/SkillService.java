package com.superagent.knowledge.skill;

import com.superagent.api.skill.SkillDto;
import com.superagent.api.skill.SkillImageUpload;
import com.superagent.api.skill.SkillRequest;
import com.superagent.core.org.TeamPaths;
import com.superagent.core.org.TeamRenamed;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.org.TeamAdminStore;
import com.superagent.core.skill.Skill;
import com.superagent.core.skill.SkillStore;
import com.superagent.knowledge.KnowledgeSpace;
import com.superagent.knowledge.KnowledgeSpaces;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skills: working standards agents follow. Like the knowledge base they live in a layer — the user's own,
 * a team's, or the company's — with the same rules (see {@link KnowledgeSpaces}). Skill text lives in the DB; images on disk
 * under {@code superagent.skill.dir/<skillId>/images/}.
 *
 * <p>At run time skills are offered by <em>progressive disclosure</em>: {@link #install} copies the
 * run's skills into its workspace ({@code .superagent/skills/<id>/SKILL.md} + images) and returns a
 * short index — one line per skill, name + when to use it — for the system prompt. The agent reads
 * a skill's full text only when its work matches, so unused skills cost one line, not a document.
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final int MAX_CONTENT_CHARS = 200_000;
    private static final String SEEDED = "skill.seeded";

    private final SkillStore store;
    private final SystemSettingStore settings;
    private final KnowledgeSpaces spaces;
    private final TeamAdminStore teamAdmins;
    private final Path root;

    public SkillService(SkillStore store, SystemSettingStore settings, KnowledgeSpaces spaces, TeamAdminStore teamAdmins,
                        @Value("${superagent.skill.dir:./data/skills}") String dir) {
        this.teamAdmins = teamAdmins;
        this.store = store;
        this.settings = settings;
        this.spaces = spaces;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        seedOnce();
    }

    // --- CRUD ---

    /** What the user can see: their own skills, their teams' (and the teams above), the company's. */
    public List<SkillDto> list(String user) {
        Predicate<Skill> visible = visibleTo(user);
        Predicate<Skill> writable = editableBy(user);
        return live().filter(visible)
                .sorted(Comparator.comparingInt((Skill s) -> spaceOf(s).kind().ordinal())
                        .thenComparing(s -> spaceOf(s).key())
                        .thenComparing(s -> s.category() == null ? "￿" : s.category())
                        .thenComparing(Skill::name, String.CASE_INSENSITIVE_ORDER))
                .map(s -> toDto(s, writable)).toList();
    }

    public SkillDto get(String id, String user) {
        return toDto(readable(id, user), editableBy(user));
    }

    public SkillDto create(SkillRequest req, String user) {
        Skill s = insert(req, target(req.space(), user), user);
        log.info("skill {} ({}) created by {} in {}", s.id(), s.name(), user, s.space());
        return toDto(s, editableBy(user));
    }

    /** Only those who may write the skill's layer may change it — or move it to another layer they may write. */
    public SkillDto update(String id, SkillRequest req, String user) {
        Skill cur = writable(id, user);
        Skill s = new Skill(cur.id(),
                req.name() == null ? cur.name() : name(req.name()),
                req.description() == null ? cur.description() : text(req.description(), 500, "description"),
                req.category() == null ? cur.category() : text(req.category(), 40, "category"),
                req.content() == null ? cur.content() : content(req.content()),
                req.space() == null ? cur.space() : stored(target(req.space(), user)),
                req.enabled() == null ? cur.enabled() : req.enabled(),
                false, cur.createdBy(), user, cur.createdAt(), Instant.now());
        store.save(s);
        return toDto(s, editableBy(user));
    }

    public void delete(String id, String user) {
        store.save(writable(id, user).withDeleted(user));
    }

    // --- images ---

    public SkillDto addImage(String id, SkillImageUpload upload, String user) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(upload.data() == null ? "" : upload.data());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image is not valid base64");
        }
        addImage(id, upload.name(), bytes, user);
        return get(id, user);
    }

    /** Stores an image (raster formats only, sniffed from the bytes) and returns the name to reference it by. */
    public String addImage(String id, String requestedName, byte[] bytes, String user) {
        Skill s = writable(id, user);
        if (bytes.length > ImageAttachmentStore.MAX_BYTES) {
            throw new IllegalArgumentException("Image exceeds " + ImageAttachmentStore.MAX_BYTES / 1024 / 1024 + " MB");
        }
        String type = ImageAttachmentStore.sniff(bytes);
        if (type == null) {
            throw new IllegalArgumentException("Only PNG, JPEG, GIF and WebP images are supported");
        }
        Path dir = imageDir(id);
        String name = freeName(dir, imageBase(requestedName), ImageAttachmentStore.extension(type));
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        store.save(new Skill(s.id(), s.name(), s.description(), s.category(), s.content(), s.space(), s.enabled(), false,
                s.createdBy(), user, s.createdAt(), Instant.now()));
        return name;
    }

    public ImageAttachmentStore.StoredImage readImage(String id, String name, String user) {
        readable(id, user);
        Path file = imageFile(id, name);
        try {
            byte[] bytes = Files.readAllBytes(file);
            String type = ImageAttachmentStore.sniff(bytes);
            if (type == null) {
                throw new SkillNotFoundException(id + "/" + name);
            }
            return new ImageAttachmentStore.StoredImage(bytes, type);
        } catch (IOException e) {
            throw new SkillNotFoundException(id + "/" + name);
        }
    }

    public SkillDto deleteImage(String id, String name, String user) {
        writable(id, user);
        try {
            Files.deleteIfExists(imageFile(id, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return get(id, user);
    }

    // --- bindings ---

    /** The agent's skills that still exist, in the order they were attached. */
    public List<String> agentSkillIds(String agentId) {
        Map<String, Skill> live = live().collect(Collectors.toMap(Skill::id, Function.identity()));
        return store.binding(SkillStore.AGENT, agentId).orElse(List.of()).stream().filter(live::containsKey).toList();
    }

    /**
     * Replaces the agent's skills. The user (its owner) may attach skills they can see; skills already attached
     * stay attachable even if the user has since lost sight of them, so re-saving an agent never fails on them.
     */
    public void setAgentSkills(String agentId, List<String> skillIds, String user) {
        Set<String> bound = Set.copyOf(store.binding(SkillStore.AGENT, agentId).orElse(List.of()));
        Predicate<KnowledgeSpace> readable = spaces.readableBy(user);
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(skillIds));
        for (String id : ids) {
            if (!readable.test(spaceOf(require(id))) && !bound.contains(id)) {
                throw new SkillNotFoundException(id);
            }
        }
        store.bind(SkillStore.AGENT, agentId, ids);
    }

    /** A task an agent creates works with that agent's skills. */
    public void bindTaskToAgent(String taskId, String agentId) {
        store.bind(SkillStore.TASK, taskId, agentSkillIds(agentId));
    }

    /** The agent's enabled skills that its owner can still see (one who left a team loses that team's skills). */
    public List<Skill> forAgent(String agentId, String owner) {
        return enabled(agentSkillIds(agentId), owner);
    }

    /** An agent-created task uses its agent's skills; a task with no agent sees every skill its owner can. */
    public List<Skill> forTask(String taskId, String owner) {
        Predicate<KnowledgeSpace> readable = spaces.readableBy(owner);
        return store.binding(SkillStore.TASK, taskId).map(ids -> enabled(ids, owner))
                .orElseGet(() -> live().filter(s -> s.enabled() && readable.test(spaceOf(s))).toList());
    }

    private List<Skill> enabled(List<String> ids, String owner) {
        Predicate<KnowledgeSpace> readable = spaces.readableBy(owner);
        return ids.stream().map(store::find).flatMap(Optional::stream)
                .filter(s -> s.enabled() && !s.deleted() && readable.test(spaceOf(s))).toList();
    }

    @EventListener
    void onTeamRenamed(TeamRenamed event) {
        for (Skill s : store.list()) {
            KnowledgeSpace space = spaceOf(s);
            if (space.kind() == KnowledgeSpace.Kind.TEAM && TeamPaths.within(space.key(), event.from())) {
                String team = event.to() + space.key().substring(event.from().length());
                store.save(new Skill(s.id(), s.name(), s.description(), s.category(), s.content(),
                        stored(KnowledgeSpace.team(team)), s.enabled(), s.deleted(), s.createdBy(), s.updatedBy(),
                        s.createdAt(), Instant.now()));
            }
        }
    }

    // --- run-time install ---

    /**
     * Writes {@code skills} into the run's workspace (replacing whatever an earlier run left) and
     * returns the system-prompt index, or null when there are none. Never fails the run: a skill
     * that can't be installed is just left out.
     */
    public String install(String workspaceDir, List<Skill> skills, boolean canManage) {
        if (workspaceDir == null) {
            return null;
        }
        Path superagentDir = Path.of(workspaceDir).resolve(".superagent");
        Path skillsDir = superagentDir.resolve("skills");
        // The agent owns its workspace; refuse to write through a link it may have planted.
        if (Files.isSymbolicLink(superagentDir) || Files.isSymbolicLink(skillsDir)) {
            log.warn("workspace {}: .superagent is a symlink; skills not installed", workspaceDir);
            return null;
        }
        deleteTree(skillsDir);
        if (skills.isEmpty()) {
            return canManage ? manageHint() : null;
        }
        StringBuilder index = new StringBuilder("# Skills\n\nYou have these skills — working standards to follow. "
                + "Before starting work that matches a skill's description, read its SKILL.md with your file-reading "
                + "tool and follow it; images it references sit next to it under images/.\n\n");
        for (Skill s : skills) {
            try {
                Path dir = skillsDir.resolve(s.id());
                Files.createDirectories(dir.resolve("images"));
                Files.writeString(dir.resolve("SKILL.md"), "# " + s.name() + "\n\n"
                        + (s.description() == null ? "" : "> " + s.description() + "\n\n") + s.content(),
                        StandardCharsets.UTF_8);
                for (String image : images(s.id())) {
                    Files.copy(imageDir(s.id()).resolve(image), dir.resolve("images").resolve(image));
                }
                index.append("- ").append(s.name())
                        .append(s.description() == null ? "" : " — " + s.description())
                        .append(" (").append(dir.resolve("SKILL.md")).append(")\n");
            } catch (IOException e) {
                log.warn("skill {} not installed into {}: {}", s.id(), workspaceDir, e.getMessage());
            }
        }
        if (canManage) {
            index.append("\n").append(manageHint());
        }
        return index.toString();
    }

    private static String manageHint() {
        return "When the user asks to create or change a skill, use the skill_list / skill_get / skill_create / "
                + "skill_update tools; skill_add_image adds an image from your workspace to a skill.";
    }

    // --- helpers ---

    private Stream<Skill> live() {
        return store.list().stream().filter(s -> !s.deleted());
    }

    Skill require(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new SkillNotFoundException(id);
        }
        return store.find(id).filter(s -> !s.deleted()).orElseThrow(() -> new SkillNotFoundException(id));
    }

    /** A skill the user can't see reads as not found. */
    private Skill readable(String id, String user) {
        Skill s = require(id);
        if (!visibleTo(user).test(s)) {
            throw new SkillNotFoundException(id);
        }
        return s;
    }

    /** The knowledge layers' read rule, plus: whoever manages a team's skills (system or team admin) sees them. */
    private Predicate<Skill> visibleTo(String user) {
        Predicate<KnowledgeSpace> readable = spaces.readableBy(user);
        boolean admin = spaces.isAdmin(user);
        return s -> {
            KnowledgeSpace space = spaceOf(s);
            return readable.test(space) || space.kind() == KnowledgeSpace.Kind.TEAM
                    && (admin || teamAdmins.manages(user, space.key()));
        };
    }

    private Skill writable(String id, String user) {
        Skill s = readable(id, user);
        if (!editableBy(user).test(s)) {
            throw new SkillForbiddenException("This skill is read-only for you");
        }
        return s;
    }

    /**
     * Who may edit or delete a skill: personal — its owner; company — system administrators; team — whoever
     * created it (while still in the team), an admin of that team or a team above it, or a system administrator.
     * Adding a skill to a team stays open to its members (see {@link #target}).
     */
    private Predicate<Skill> editableBy(String user) {
        boolean admin = spaces.isAdmin(user);
        Predicate<KnowledgeSpace> member = spaces.writableBy(user);
        return s -> {
            KnowledgeSpace space = spaceOf(s);
            return switch (space.kind()) {
                case PERSONAL -> space.key().equals(user);
                case COMPANY -> admin;
                case TEAM -> admin || teamAdmins.manages(user, space.key())
                        || (user != null && user.equals(s.createdBy()) && member.test(space));
            };
        };
    }

    /** The layer a new or moved skill goes to; blank = the user's own. */
    private KnowledgeSpace target(String spaceId, String user) {
        KnowledgeSpace space = spaces.resolve(user, spaceId, false);
        if (!spaces.writableBy(user).test(space)) {
            throw new SkillForbiddenException("You can't add skills to " + space.id());
        }
        return space;
    }

    private Skill insert(SkillRequest req, KnowledgeSpace space, String user) {
        Instant now = Instant.now();
        Skill s = new Skill(UUID.randomUUID().toString(), name(req.name()), text(req.description(), 500, "description"),
                text(req.category(), 40, "category"), content(req.content()), stored(space),
                req.enabled() == null || req.enabled(), false, user, user, now, now);
        store.save(s);
        return s;
    }

    /** Stored as {@code personal:<user>}, {@code team:<path>} or {@code company}; blank is a pre-layers skill = company. */
    static KnowledgeSpace spaceOf(Skill s) {
        String v = s.space();
        if (v != null && v.startsWith("personal:")) {
            return KnowledgeSpace.personal(v.substring("personal:".length()));
        }
        if (v != null && v.startsWith("team:")) {
            return KnowledgeSpace.team(v.substring("team:".length()));
        }
        return KnowledgeSpace.company();
    }

    private static String stored(KnowledgeSpace space) {
        return space.kind() == KnowledgeSpace.Kind.PERSONAL ? "personal:" + space.key() : space.id();
    }

    public List<String> images(String id) {
        Path dir = imageDir(id);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path imageDir(String id) {
        if (!ID.matcher(id).matches()) {
            throw new SkillNotFoundException(id);
        }
        return root.resolve(id).resolve("images");
    }

    private Path imageFile(String id, String name) {
        if (name == null || !images(id).contains(name)) {
            throw new SkillNotFoundException(id + "/" + name);
        }
        return imageDir(id).resolve(name);
    }

    /** A safe base name: letters/digits/._- only, no leading dot, extension dropped (it comes from the bytes). */
    private static String imageBase(String requested) {
        String n = requested == null ? "" : requested.strip();
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        n = n.replaceAll("[^\\p{L}\\p{N}_-]+", "-").replaceAll("^-+|-+$", "");
        if (n.length() > 60) {
            n = n.substring(0, 60);
        }
        return n.isEmpty() ? "image" : n;
    }

    private static String freeName(Path dir, String base, String ext) {
        String name = base + "." + ext;
        for (int i = 2; Files.exists(dir.resolve(name), LinkOption.NOFOLLOW_LINKS); i++) {
            name = base + "-" + i + "." + ext;
        }
        return name;
    }

    private static String name(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 100) {
            throw new IllegalArgumentException("Skill name is required (at most 100 characters)");
        }
        return n;
    }

    private static String text(String value, int max, String field) {
        String v = value == null ? "" : value.strip();
        if (v.length() > max) {
            throw new IllegalArgumentException(field + " is longer than " + max + " characters");
        }
        return v.isEmpty() ? null : v;
    }

    private static String content(String content) {
        String c = content == null ? "" : content;
        if (c.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("Skill content is longer than " + MAX_CONTENT_CHARS + " characters");
        }
        return c;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) { // doesn't follow links: a link is removed, not its target
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SkillDto toDto(Skill s, Predicate<Skill> writable) {
        KnowledgeSpace space = spaceOf(s);
        return new SkillDto(s.id(), s.name(), s.description(), s.category(), s.content(), s.enabled(), space.id(),
                writable.test(s), images(s.id()), s.createdBy(), s.updatedBy(), s.createdAt(), s.updatedAt());
    }

    /** Examples on first start only; deleting them later doesn't bring them back. */
    private void seedOnce() {
        if (settings.all().containsKey(SEEDED)) {
            return;
        }
        if (live().findAny().isEmpty()) {
            for (SkillRequest seed : SkillSeeds.ALL) {
                insert(seed, KnowledgeSpace.company(), "system");
            }
        }
        settings.put(SEEDED, "true");
    }
}
