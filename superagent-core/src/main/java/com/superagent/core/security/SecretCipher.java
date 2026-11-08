package com.superagent.core.security;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStore;
import com.superagent.core.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encrypts secrets users hand to SuperAgent (e.g. a bot's app secret) before they are stored.
 * AES-256-GCM. The master key comes from {@code SUPERAGENT_SECRET_KEY} (base64, 32 bytes) or, when that is
 * unset, from a key file created once with owner-only permissions. Losing it makes stored secrets
 * unreadable — users then re-enter them.
 *
 * <p>Envelope encryption: a company's secrets ({@code v2:<company>:…}) are encrypted with that company's own
 * data key, which is kept in its {@link Company} row encrypted with the master key. Ciphertext is bound to its
 * company and only decrypts inside it. Work outside any company uses the master key directly ({@code v1:}).
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final String COMPANY_PREFIX = "v2:";
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final CompanyStore companies;
    private final Map<String, SecretKeySpec> dataKeys = new ConcurrentHashMap<>();

    /** Master key only, for tools and tests that have no companies. */
    public SecretCipher(String configuredKey, String keyFile) {
        this(configuredKey, keyFile, null);
    }

    @Autowired
    public SecretCipher(@Value("${superagent.secret.key:${SUPERAGENT_SECRET_KEY:}}") String configuredKey,
                        @Value("${superagent.secret.key-file:./data/secret.key}") String keyFile,
                        CompanyStore companies) {
        this.key = new SecretKeySpec(loadKey(configuredKey, Path.of(keyFile)), "AES");
        this.companies = companies;
    }

    public String encrypt(String plain) {
        String company = companies == null ? null : TenantContext.current().orElse(null);
        if (company == null) {
            return PREFIX + seal(key, plain, null);
        }
        return COMPANY_PREFIX + company + ":" + seal(dataKey(company), plain, company);
    }

    /** Throws IllegalStateException when the value wasn't encrypted with the current key. */
    public String decrypt(String stored) {
        if (stored != null && stored.startsWith(COMPANY_PREFIX)) {
            int sep = stored.indexOf(':', COMPANY_PREFIX.length());
            String company = sep < 0 ? "" : stored.substring(COMPANY_PREFIX.length(), sep);
            if (companies == null || company.isEmpty()
                    || !company.equals(TenantContext.current().orElse(null))) {
                throw new IllegalStateException("Secret belongs to another company");
            }
            return open(dataKey(company), stored.substring(sep + 1), company);
        }
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Secret is not in a recognised format");
        }
        return open(key, stored.substring(PREFIX.length()), null);
    }

    /** The company's data key, made (and stored, wrapped by the master key) the first time it is needed. */
    private SecretKeySpec dataKey(String company) {
        return dataKeys.computeIfAbsent(company, id -> {
            synchronized (dataKeys) {
                Company c = companies.find(id).orElseThrow(() -> new IllegalStateException("Unknown company: " + id));
                if (c.dataKeyCipher() != null) {
                    return new SecretKeySpec(Base64.getDecoder().decode(
                            open(key, c.dataKeyCipher().substring(PREFIX.length()), "company:" + id)), "AES");
                }
                byte[] fresh = new byte[32];
                random.nextBytes(fresh);
                companies.save(c.withDataKey(PREFIX + seal(key, Base64.getEncoder().encodeToString(fresh), "company:" + id)));
                return new SecretKeySpec(fresh, "AES");
            }
        });
    }

    private String seal(SecretKeySpec k, String plain, String boundTo) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
            if (boundTo != null) {
                c.updateAAD(boundTo.getBytes(StandardCharsets.UTF_8));
            }
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt secret", e);
        }
    }

    private static String open(SecretKeySpec k, String sealed, String boundTo) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, in, 0, 12));
            if (boundTo != null) {
                c.updateAAD(boundTo.getBytes(StandardCharsets.UTF_8));
            }
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
