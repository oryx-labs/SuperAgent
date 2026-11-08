package com.superagent.platform.user;

import com.superagent.core.tenant.TenantPaths;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a signed-in user changes about their own account: their avatar and their password. Avatars are small
 * images on disk under the company's directory, one per username (file named by its hash, so any username is a safe file name); their
 * modification time is the version clients use to refresh a cached picture.
 */
@Service
public class AccountService {

    public static final int AVATAR_MAX_BYTES = 1024 * 1024;
    static final int MIN_PASSWORD = 8;

    private final UserStore users;
    private final PasswordEncoder encoder;
    private final String adminUsername;
    private final Path avatars;

    public AccountService(UserStore users, PasswordEncoder encoder,
                          @Value("${superagent.auth.admin.username:admin}") String adminUsername,
                          @Value("${superagent.avatars.dir:./data/avatars}") String avatarsDir) {
        this.users = users;
        this.encoder = encoder;
        this.adminUsername = adminUsername;
        this.avatars = Path.of(avatarsDir).toAbsolutePath().normalize();
    }

    /** Only username/password accounts have a password to change; the built-in admin's comes from configuration. */
    public boolean canChangePassword(String username) {
        return !adminUsername.equals(username)
                && users.findByUsername(username).map(u -> u.passwordHash() != null && !u.passwordHash().isBlank())
                .orElse(false);
    }

    public void changePassword(String username, String current, String next) {
        if (adminUsername.equals(username)) {
            throw new IllegalArgumentException("内置管理员的密码在配置文件中设置，不能在这里修改");
        }
        User user = users.findByUsername(username).orElseThrow(() -> new UserNotFoundException(username));
        if (user.passwordHash() == null || user.passwordHash().isBlank()) {
            throw new IllegalArgumentException("这个账号不用密码登录");
        }
        if (current == null || !encoder.matches(current, user.passwordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        if (next == null || next.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("新密码至少 " + MIN_PASSWORD + " 位");
        }
        if (next.equals(current)) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        users.save(user.withPasswordHash(encoder.encode(next)));
    }

    /** Replaces the user's avatar (PNG, JPEG, GIF or WebP, sniffed from the bytes); returns its new version. */
    public long setAvatar(String username, String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64 == null ? "" : base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > AVATAR_MAX_BYTES) {
            throw new IllegalArgumentException("头像图片不能超过 1 MB");
        }
        if (ImageAttachmentStore.sniff(bytes) == null) {
            throw new IllegalArgumentException("头像只支持 PNG、JPEG、GIF、WebP 图片");
        }
        Path file = avatarFile(username);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "avatar-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void removeAvatar(String username) {
        try {
            Files.deleteIfExists(avatarFile(username));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The user's avatar version, or null when they have none. */
    public Long avatarVersion(String username) {
        Path file = avatarFile(username);
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Everyone who has an avatar → its version, so a page can show any user's picture without asking per user. */
    public Map<String, Long> avatarVersions() {
        Map<String, Long> out = new LinkedHashMap<>();
        Long admin = avatarVersion(adminUsername);
        if (admin != null) {
            out.put(adminUsername, admin);
        }
        users.listUsers().forEach(u -> {
            Long v = avatarVersion(u.username());
            if (v != null) {
                out.put(u.username(), v);
            }
        });
        return out;
    }

    public Optional<ImageAttachmentStore.StoredImage> avatar(String username) {
        Path file = avatarFile(username);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String type = ImageAttachmentStore.sniff(bytes);
            return type == null ? Optional.empty() : Optional.of(new ImageAttachmentStore.StoredImage(bytes, type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path avatarFile(String username) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((username == null ? "" : username)
                    .getBytes(StandardCharsets.UTF_8));
            return TenantPaths.of(avatars).resolve(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
