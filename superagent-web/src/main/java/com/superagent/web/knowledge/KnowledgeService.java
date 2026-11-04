package com.superagent.web.knowledge;

import com.superagent.api.knowledge.KnowledgeEntryDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The built-in ("local") knowledge base: a plain directory tree on disk, one private root per
 * user under {@code superagent.knowledge.dir}. The disk is the source of truth — there is no
 * index to drift out of sync.
 *
 * <p>Every client path is untrusted: it is split into validated segments (no {@code ..},
 * separators, dot-names or control characters), resolved under the owner's root, and refused
 * if any component is a symlink, so a request can never reach outside that root.
 */
@Service
public class KnowledgeService {

    /** Files larger than this aren't served for preview/download. */
    static final long MAX_READ_BYTES = 20L * 1024 * 1024;
    private static final int MAX_FOLDERS = 5000;
    private static final Pattern PLAIN_OWNER = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");

    private final Path root;

    public KnowledgeService(@Value("${superagent.knowledge.dir:./data/knowledge}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** A file's bytes plus its name, for the controller to pick a content type. */
    public record FileContent(String name, byte[] bytes) {
    }

    public List<KnowledgeEntryDto> list(String owner, String path) {
        Path base = ownerRoot(owner);
        Path dir = existing(base, path);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a folder: " + path);
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(KnowledgeService::visible).filter(p -> !Files.isSymbolicLink(p))
                    .map(p -> entry(base, p))
                    .sorted(Comparator.comparing(KnowledgeEntryDto::dir).reversed()
                            .thenComparing(KnowledgeEntryDto::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every folder path (root first, as ""), for the move-to picker. */
    public List<String> folders(String owner) {
        Path base = ownerRoot(owner);
        List<String> out = new ArrayList<>();
        out.add("");
        try (Stream<Path> all = Files.walk(base)) { // walk doesn't follow symlinks by default
            all.filter(p -> !p.equals(base) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !hasHiddenSegment(base, p))
                    .limit(MAX_FOLDERS)
                    .forEach(p -> out.add(rel(base, p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public FileContent read(String owner, String path) {
        Path file = existing(ownerRoot(owner), path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a file: " + path);
        }
        try {
            if (Files.size(file) > MAX_READ_BYTES) {
                throw new IllegalArgumentException("File is larger than " + MAX_READ_BYTES / (1024 * 1024) + " MB");
            }
            return new FileContent(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public KnowledgeEntryDto createFolder(String owner, String path) {
        Path base = ownerRoot(owner);
        Path dir = resolve(base, path);
        if (dir.equals(base)) {
            throw new IllegalArgumentException("Folder name is required");
        }
        requireFolder(dir.getParent());
        try {
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException e) {
            throw new KnowledgeConflictException(dir.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entry(base, dir);
    }

    /** Creates the folder and any missing parents; an existing folder is fine. */
    public KnowledgeEntryDto ensureFolder(String owner, String path) {
        Path base = ownerRoot(owner);
        Path dir = resolve(base, path);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            requireFolder(dir);
        } else {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return entry(base, dir);
    }

    /**
     * Copies {@code source} (a file the caller has already confined, e.g. to a task workspace) into
     * {@code folder}, creating the folder if needed. A taken name gets a " (2)" suffix unless
     * {@code overwrite} is set, so saving never silently replaces knowledge.
     */
    public KnowledgeEntryDto saveFile(String owner, Path source, String folder, String name, boolean overwrite) {
        Path base = ownerRoot(owner);
        Path dir = resolve(base, ensureFolder(owner, folder).path());
        String fileName = validName(name == null || name.isBlank() ? source.getFileName().toString() : name);
        Path target = dir.resolve(fileName);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new KnowledgeConflictException(fileName);
        }
        if (!overwrite) {
            target = freeName(dir, fileName);
        }
        try {
            if (Files.size(source) > MAX_READ_BYTES) {
                throw new IllegalArgumentException("File is larger than " + MAX_READ_BYTES / (1024 * 1024) + " MB");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entry(base, target);
    }

    /** Writes text content as a new file in {@code folder} (created if needed); same naming rules as {@link #saveFile}. */
    public KnowledgeEntryDto writeText(String owner, String folder, String name, String content, boolean overwrite) {
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_READ_BYTES) {
            throw new IllegalArgumentException("Content is larger than " + MAX_READ_BYTES / (1024 * 1024) + " MB");
        }
        Path base = ownerRoot(owner);
        Path dir = resolve(base, ensureFolder(owner, folder).path());
        String fileName = validName(name);
        Path target = dir.resolve(fileName);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new KnowledgeConflictException(fileName);
        }
        if (!overwrite) {
            target = freeName(dir, fileName);
        }
        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entry(base, target);
    }

    private static Path freeName(Path dir, String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        Path candidate = dir.resolve(fileName);
        for (int n = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); n++) {
            candidate = dir.resolve(stem + " (" + n + ")" + ext);
        }
        return candidate;
    }

    public KnowledgeEntryDto rename(String owner, String path, String newName) {
        Path base = ownerRoot(owner);
        Path from = existingNonRoot(base, path);
        return moveTo(base, from, from.resolveSibling(validName(newName)));
    }

    public KnowledgeEntryDto move(String owner, String path, String targetDir) {
        Path base = ownerRoot(owner);
        Path from = existingNonRoot(base, path);
        Path dir = existing(base, targetDir);
        requireFolder(dir);
        if (dir.startsWith(from)) {
            throw new IllegalArgumentException("Cannot move a folder into itself");
        }
        return moveTo(base, from, dir.resolve(from.getFileName()));
    }

    /** Deletes a file, or a folder with everything in it. Symlinks inside are removed, never followed. */
    public void delete(String owner, String path) {
        Path target = existingNonRoot(ownerRoot(owner), path);
        try (Stream<Path> all = Files.walk(target)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- path guards ---

    private KnowledgeEntryDto moveTo(Path base, Path from, Path to) {
        if (from.equals(to)) {
            return entry(base, from);
        }
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            throw new KnowledgeConflictException(to.getFileName().toString());
        }
        try {
            Files.move(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entry(base, to);
    }

    Path ownerRoot(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("No owner");
        }
        Path dir = root.resolve(ownerDirName(owner));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    /** Readable for ordinary usernames; anything else is hashed so it can't shape the path. */
    static String ownerDirName(String owner) {
        if (PLAIN_OWNER.matcher(owner).matches()) {
            return owner;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            return "u-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Resolves a client path under {@code base}; the target itself need not exist. */
    private static Path resolve(Path base, String path) {
        Path p = base;
        if (path != null) {
            for (String segment : path.split("/", -1)) {
                if (!segment.isEmpty()) {
                    p = p.resolve(validName(segment));
                }
            }
        }
        if (!p.normalize().startsWith(base)) {
            throw new KnowledgeNotFoundException(path);
        }
        // No component may be a symlink: it could point anywhere on the host.
        for (Path cur = p; !cur.equals(base); cur = cur.getParent()) {
            if (Files.isSymbolicLink(cur)) {
                throw new KnowledgeNotFoundException(path);
            }
        }
        return p;
    }

    private static Path existing(Path base, String path) {
        Path p = resolve(base, path);
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new KnowledgeNotFoundException(path);
        }
        return p;
    }

    private static Path existingNonRoot(Path base, String path) {
        Path p = existing(base, path);
        if (p.equals(base)) {
            throw new IllegalArgumentException("The root folder can't be changed");
        }
        return p;
    }

    private static void requireFolder(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Target is not a folder");
        }
    }

    static String validName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 255 || n.startsWith(".")
                || n.contains("/") || n.contains("\\") || n.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("Invalid name: " + name);
        }
        return n;
    }

    // Dot-entries (.DS_Store, .git, ...) are infrastructure, not knowledge.
    private static boolean visible(Path p) {
        return !p.getFileName().toString().startsWith(".");
    }

    private static boolean hasHiddenSegment(Path base, Path p) {
        for (Path seg : base.relativize(p)) {
            if (seg.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String rel(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    private static KnowledgeEntryDto entry(Path base, Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new KnowledgeEntryDto(p.getFileName().toString(), rel(base, p), a.isDirectory(),
                    a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
