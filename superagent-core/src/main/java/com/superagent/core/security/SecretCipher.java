package com.superagent.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets users hand to SuperAgent (e.g. a bot's app secret) before they are stored.
 * AES-256-GCM; the key comes from {@code SUPERAGENT_SECRET_KEY} (base64, 32 bytes) or, when that is
 * unset, from a key file created once with owner-only permissions. Losing the key makes stored
 * secrets unreadable — users then re-enter them.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(@Value("${superagent.secret.key:${SUPERAGENT_SECRET_KEY:}}") String configuredKey,
                        @Value("${superagent.secret.key-file:./data/secret.key}") String keyFile) {
        this.key = new SecretKeySpec(loadKey(configuredKey, Path.of(keyFile)), "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt secret", e);
        }
    }

    /** Throws IllegalStateException when the value wasn't encrypted with the current key. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Secret is not in a recognised format");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt secret (was the key changed?)", e);
        }
    }

    private byte[] loadKey(String configured, Path file) {
        try {
            if (configured != null && !configured.isBlank()) {
                return check(Base64.getDecoder().decode(configured.trim()));
            }
            if (Files.exists(file)) {
                return check(Base64.getDecoder().decode(Files.readString(file).trim()));
            }
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(fresh));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system: rely on the directory's permissions
            }
            return fresh;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create the secret key file " + file, e);
        }
    }

    private static byte[] check(byte[] k) {
        if (k.length != 32) {
            throw new IllegalStateException("Secret key must be 32 bytes (base64-encoded)");
        }
        return k;
    }
}
