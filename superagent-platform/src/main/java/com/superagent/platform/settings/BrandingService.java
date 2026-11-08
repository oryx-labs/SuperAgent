package com.superagent.platform.settings;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.tenant.TenantPaths;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * The company's own logo, shown on the sign-in page and at the top left. Without one, SuperAgent's logo (bundled
 * with the UI) is shown. Kept as a single image file on disk per company ({@code <dir>/<companyId>/logo}); raster
 * formats only, so it can never carry script.
 */
@Service
public class BrandingService {

    public static final int LOGO_MAX_BYTES = 1024 * 1024;
    private static final String DEFAULT_LOGO = "static/superagent-logo.png";

    private final Path root;

    public BrandingService(@Value("${superagent.branding.dir:./data/branding}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * The logo to show: the company's own if one is set, else the default. Public (the sign-in page has no user),
     * so with no company in context it is the default company's.
     */
    public ImageAttachmentStore.StoredImage logo() {
        Path logo = TenantPaths.of(root, TenantContext.current().orElse(Company.DEFAULT_ID)).resolve("logo");
        try {
            if (Files.isRegularFile(logo)) {
                byte[] bytes = Files.readAllBytes(logo);
                String type = ImageAttachmentStore.sniff(bytes);
                if (type != null) {
                    return new ImageAttachmentStore.StoredImage(bytes, type);
                }
            }
            try (InputStream in = new ClassPathResource(DEFAULT_LOGO).getInputStream()) {
                return new ImageAttachmentStore.StoredImage(in.readAllBytes(), "image/png");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean custom() {
        return Files.isRegularFile(logoFile());
    }

    /** Sets the company logo (PNG, JPEG, GIF or WebP, at most 1 MB, sniffed from the bytes). */
    public void setLogo(String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64 == null ? "" : base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > LOGO_MAX_BYTES) {
            throw new IllegalArgumentException("Logo 图片不能超过 1 MB");
        }
        if (ImageAttachmentStore.sniff(bytes) == null) {
            throw new IllegalArgumentException("Logo 只支持 PNG、JPEG、GIF、WebP 图片");
        }
        Path logo = logoFile();
        try {
            Files.createDirectories(logo.getParent());
            Path tmp = Files.createTempFile(logo.getParent(), "logo-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, logo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Back to SuperAgent's logo. */
    public void resetLogo() {
        try {
            Files.deleteIfExists(logoFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path logoFile() {
        return TenantPaths.of(root).resolve("logo");
    }
}
