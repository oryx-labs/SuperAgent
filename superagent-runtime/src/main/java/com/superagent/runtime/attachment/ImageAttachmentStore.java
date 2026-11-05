package com.superagent.runtime.attachment;

import com.superagent.api.chat.ImageUploadDto;
import com.superagent.core.chat.ImageAttachment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Images attached to chat and task messages, stored on local disk as
 * {@code <dir>/<ownerId>/<messageId>/<n>.<ext>} (owner = chat session or task). Kept apart
 * from workspaces because a task has no workspace until it is scheduled, and so the agent
 * can't overwrite them. The format is sniffed from the bytes — the declared type is not
 * trusted — and only raster formats are accepted, so serving them back can't run script.
 */
@Component
public class ImageAttachmentStore {

    public static final int MAX_IMAGES = 5;
    /** The Claude API's per-image limit. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    /** Sent to the agent when a message is only images: some CLIs refuse an empty prompt. */
    public static final String IMAGE_ONLY_PROMPT = "See the attached image(s).";

    /** Ids are server-minted UUIDs; anything else (e.g. "../x") never reaches the file system. */
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");

    /** A stored image read back for serving. */
    public record StoredImage(byte[] bytes, String mediaType) {

        /** Inline response; stored images never change, so the browser may cache them privately. */
        public ResponseEntity<byte[]> toResponse() {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mediaType))
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                    .body(bytes);
        }
    }

    private final Path root;

    public ImageAttachmentStore(@Value("${superagent.attachments.dir:./data/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** Validates and stores a message's images; returns them in upload order. Throws IllegalArgumentException on bad input. */
    public List<ImageAttachment> save(String ownerId, String messageId, List<ImageUploadDto> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            return List.of();
        }
        if (uploads.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("At most " + MAX_IMAGES + " images per message");
        }
        List<byte[]> decoded = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (ImageUploadDto upload : uploads) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(upload.data() == null ? "" : upload.data());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Image is not valid base64");
            }
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("Image exceeds " + (MAX_BYTES / 1024 / 1024) + " MB");
            }
            String type = sniff(bytes);
            if (type == null) {
                throw new IllegalArgumentException("Only PNG, JPEG, GIF and WebP images are supported");
            }
            decoded.add(bytes);
            types.add(type);
        }
        Path dir = messageDir(ownerId, messageId);
        List<ImageAttachment> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (int i = 0; i < decoded.size(); i++) {
                Path file = dir.resolve(i + "." + extension(types.get(i)));
                Files.write(file, decoded.get(i));
                out.add(new ImageAttachment(file.toString(), types.get(i)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store images for message " + messageId, e);
        }
        return out;
    }

    /** A message's stored images in upload order; empty when it has none. */
    public List<ImageAttachment> list(String ownerId, String messageId) {
        if (!valid(ownerId) || !valid(messageId)) {
            return List.of();
        }
        Path dir = messageDir(ownerId, messageId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> index(f) >= 0)
                    .sorted(Comparator.comparingInt(ImageAttachmentStore::index))
                    .map(f -> new ImageAttachment(f.toString(), mediaType(f)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list images for message " + messageId, e);
        }
    }

    public StoredImage read(String ownerId, String messageId, int index) {
        List<ImageAttachment> images = list(ownerId, messageId);
        if (index < 0 || index >= images.size()) {
            throw new AttachmentNotFoundException(messageId, index);
        }
        ImageAttachment image = images.get(index);
        try {
            return new StoredImage(Files.readAllBytes(Path.of(image.path())), image.mediaType());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read image " + index + " of message " + messageId, e);
        }
    }

    /** URLs of a message's images under {@code basePath} (e.g. "/api/v1/sessions/{id}/messages"). */
    public List<String> urls(String basePath, String ownerId, String messageId) {
        int count = list(ownerId, messageId).size();
        List<String> urls = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            urls.add(basePath + "/" + messageId + "/images/" + i);
        }
        return urls;
    }

    private Path messageDir(String ownerId, String messageId) {
        if (!valid(ownerId) || !valid(messageId)) {
            throw new IllegalArgumentException("Invalid id");
        }
        return root.resolve(ownerId).resolve(messageId);
    }

    private static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The real format from magic bytes, or null when it isn't one we accept. */
    public static String sniff(byte[] b) {
        if (startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(b, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (b.length >= 12 && startsWith(b, 'R', 'I', 'F', 'F')
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            default -> "webp";
        };
    }

    private static String mediaType(Path file) {
        String name = file.getFileName().toString();
        return switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "gif" -> "image/gif";
            default -> "image/webp";
        };
    }

    /** The upload index encoded in a stored file's name, or -1 for anything else. */
    private static int index(Path file) {
        String name = file.getFileName().toString();
        int dot = name.indexOf('.');
        try {
            return dot > 0 ? Integer.parseInt(name.substring(0, dot)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
