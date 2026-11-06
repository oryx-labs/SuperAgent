package com.superagent.platform.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandingServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    @Test
    void aCompanyLogoReplacesTheDefaultUntilReset(@TempDir Path dir) {
        BrandingService branding = new BrandingService(dir.toString());
        assertThat(branding.custom()).isFalse();
        assertThatThrownBy(() -> branding.setLogo(Base64.getEncoder().encodeToString("<svg onload=x>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);

        branding.setLogo(Base64.getEncoder().encodeToString(PNG));
        assertThat(branding.custom()).isTrue();
        assertThat(branding.logo().bytes()).isEqualTo(PNG);
        assertThat(branding.logo().mediaType()).isEqualTo("image/png");

        branding.resetLogo();
        assertThat(branding.custom()).isFalse();
    }
}
