package com.superagent.knowledge.skill;

import com.superagent.api.skill.SkillDto;
import com.superagent.api.skill.SkillImageUpload;
import com.superagent.api.skill.SkillRequest;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.skill.Skill;
import com.superagent.core.skill.SkillStore;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared skills: working standards agents follow. Skill text lives in the DB; images on disk
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
    private final Path root;

    public SkillService(SkillStore store, SystemSettingStore settings,
                        @Value("${superagent.skill.dir:./data/skills}") String dir) {
        this.store = store;
        this.settings = settings;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        seedOnce();
    }

    // --- CRUD ---

    public List<SkillDto> list() {
        return live().sorted(Comparator.comparing((Skill s) -> s.category() == null ? "￿" : s.category())
                        .thenComparing(Skill::name, String.CASE_INSENSITIVE_ORDER))
                .map(this::toDto).toList();
    }

    public SkillDto get(String id) {
        return toDto(require(id));
    }

    public SkillDto create(SkillRequest req, String user) {
        Instant now = Instant.now();
        Skill s = new Skill(UUID.randomUUID().toString(), name(req.name()), text(req.description(), 500, "description"),
                text(req.category(), 40, "category"), content(req.content()),
                req.enabled() == null || req.enabled(), false, user, user, now, now);
        store.save(s);
        log.info("skill {} ({}) created by {}", s.id(), s.name(), user);
        return toDto(s);
    }

    public SkillDto update(String id, SkillRequest req, String user) {
        Skill cur = require(id);
        Skill s = new Skill(cur.id(),
                req.name() == null ? cur.name() : name(req.name()),
                req.description() == null ? cur.description() : text(req.description(), 500, "description"),
                req.category() == null ? cur.category() : text(req.category(), 40, "category"),
                req.content() == null ? cur.content() : content(req.content()),
                req.enabled() == null ? cur.enabled() : req.enabled(),
                false, cur.createdBy(), user, cur.createdAt(), Instant.now());
        store.save(s);
        return toDto(s);
    }

    /** Shared means anyone may edit, but only the creator (or an admin) may delete. */
    public void delete(String id, String user, boolean admin) {
        Skill s = require(id);
        if (!admin && !(user != null && user.equals(s.createdBy()))) {
            throw new SkillForbiddenException("Only the creator or an admin can delete this skill");
        }
        store.save(s.withDeleted(user));
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
        return get(id);
    }

    /** Stores an image (raster formats only, sniffed from the bytes) and returns the name to reference it by. */
    public String addImage(String id, String requestedName, byte[] bytes, String user) {
        Skill s = require(id);
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
        store.save(new Skill(s.id(), s.name(), s.description(), s.category(), s.content(), s.enabled(), false,
                s.createdBy(), user, s.createdAt(), Instant.now()));
        return name;
    }

    public ImageAttachmentStore.StoredImage readImage(String id, String name) {
        require(id);
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

    public SkillDto deleteImage(String id, String name) {
        require(id);
        try {
            Files.deleteIfExists(imageFile(id, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return get(id);
    }

    // --- bindings ---

    /** The agent's skills that still exist, in the order they were attached. */
    public List<String> agentSkillIds(String agentId) {
        Map<String, Skill> live = live().collect(Collectors.toMap(Skill::id, Function.identity()));
        return store.binding(SkillStore.AGENT, agentId).orElse(List.of()).stream().filter(live::containsKey).toList();
    }

    public void setAgentSkills(String agentId, List<String> skillIds) {
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(skillIds));
        for (String id : ids) {
            require(id);
        }
        store.bind(SkillStore.AGENT, agentId, ids);
    }

    /** A task an agent creates works with that agent's skills. */
    public void bindTaskToAgent(String taskId, String agentId) {
        store.bind(SkillStore.TASK, taskId, agentSkillIds(agentId));
    }

    public List<Skill> forAgent(String agentId) {
        return enabled(agentSkillIds(agentId));
    }

    /** An agent-created task uses its agent's skills; a task with no agent (created on the page) sees all of them. */
    public List<Skill> forTask(String taskId) {
        return store.binding(SkillStore.TASK, taskId).map(this::enabled)
                .orElseGet(() -> live().filter(Skill::enabled).toList());
    }

    private List<Skill> enabled(List<String> ids) {
        return ids.stream().map(store::find).flatMap(Optional::stream)
                .filter(s -> s.enabled() && !s.deleted()).toList();
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

    private SkillDto toDto(Skill s) {
        return new SkillDto(s.id(), s.name(), s.description(), s.category(), s.content(), s.enabled(), images(s.id()),
                s.createdBy(), s.updatedBy(), s.createdAt(), s.updatedAt());
    }

    /** Examples on first start only; deleting them later doesn't bring them back. */
    private void seedOnce() {
        if (settings.all().containsKey(SEEDED)) {
            return;
        }
        if (live().findAny().isEmpty()) {
            for (SkillRequest seed : SkillSeeds.ALL) {
                create(seed, "system");
            }
        }
        settings.put(SEEDED, "true");
    }
}
