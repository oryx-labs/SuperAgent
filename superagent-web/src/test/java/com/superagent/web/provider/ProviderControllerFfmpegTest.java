package com.superagent.web.provider;

import com.superagent.api.provider.FfmpegDto;
import com.superagent.api.provider.InstallLogDto;
import com.superagent.platform.provider.FfmpegService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ffmpeg endpoints under /providers (admin-only by SecurityConfig's /api/v1/providers/** rule, like Node's). */
class ProviderControllerFfmpegTest {

    @Test
    void ffmpegStatusInstallAndLogAreServed() throws Exception {
        FfmpegService ffmpeg = mock(FfmpegService.class);
        FfmpegDto missing = new FfmpegDto(false, null, null, false, "没有包管理器", null, true);
        when(ffmpeg.status()).thenReturn(missing);
        when(ffmpeg.install()).thenReturn(new FfmpegDto(false, null, null, true, null, "运行 brew install ffmpeg", true));
        when(ffmpeg.installLog()).thenReturn(new InstallLogDto("install", null, Instant.now(), Instant.now(), false,
                "没有包管理器", List.of("✗ 失败：没有包管理器")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProviderController(null, null, null, null, ffmpeg)).build();

        mvc.perform(get("/api/v1/providers/ffmpeg")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installed").value(false))
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.installing").value(false))
                .andExpect(jsonPath("$.data.error").value("没有包管理器"));
        mvc.perform(post("/api/v1/providers/ffmpeg/install")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installing").value(true));
        verify(ffmpeg).install();
        mvc.perform(get("/api/v1/providers/ffmpeg/install-log")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0]").value("✗ 失败：没有包管理器"));
    }
}
