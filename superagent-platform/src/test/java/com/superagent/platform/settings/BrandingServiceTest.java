package com.superagent.platform.settings;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandingServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aCompanyLogoReplacesTheDefaultUntilReset(@TempDir Path dir) {
        TenantContext.set("acme");
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

    @Test
    void eachCompanyHasItsOwnLogoAndTheSignInPageShowsTheDefaultCompanys(@TempDir Path dir) {
        BrandingService branding = new BrandingService(dir.toString());
        TenantContext.run(Company.DEFAULT_ID, () -> branding.setLogo(Base64.getEncoder().encodeToString(PNG)));

        TenantContext.run("acme", () -> assertThat(branding.custom()).isFalse());
        assertThat(branding.logo().bytes()).isEqualTo(PNG);
    }
}
