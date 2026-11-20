package com.superagent.platform.provider;

import com.superagent.runtime.cli.CliPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Which package manager installs ffmpeg; nothing is run. */
class FfmpegServiceTest {

    private static Function<String, Optional<Path>> having(String... tools) {
        Set<String> have = Set.of(tools);
        return t -> have.contains(t) ? Optional.of(Path.of("/usr/bin", t)) : Optional.empty();
    }

    @Test
    void theSystemsOwnPackageManagerInstallsItOrNothingDoes() {
        assertThat(FfmpegService.installCommands("Mac OS X", having("brew"), false, false))
                .containsExactly(List.of("/usr/bin/brew", "install", "ffmpeg"));
        assertThat(FfmpegService.installCommands("Mac OS X", having(), false, false)).isEmpty();

        assertThat(FfmpegService.installCommands("Linux", having("apt-get", "dnf"), true, false)).containsExactly(
                List.of("/usr/bin/apt-get", "update"), List.of("/usr/bin/apt-get", "install", "-y", "ffmpeg"));
        assertThat(FfmpegService.installCommands("Linux", having("dnf"), false, true))
                .containsExactly(List.of("sudo", "-n", "/usr/bin/dnf", "install", "-y", "ffmpeg"));
        assertThat(FfmpegService.installCommands("Linux", having("yum"), true, false))
                .containsExactly(List.of("/usr/bin/yum", "install", "-y", "ffmpeg"));
        assertThat(FfmpegService.installCommands("Linux", having("apk"), true, false))
                .containsExactly(List.of("/usr/bin/apk", "add", "--no-cache", "ffmpeg"));
        // Neither root nor password-less sudo, or no package manager: the page shows how to do it by hand.
        assertThat(FfmpegService.installCommands("Linux", having("apt-get"), false, false)).isEmpty();
        assertThat(FfmpegService.installCommands("Linux", having(), true, false)).isEmpty();
        assertThat(FfmpegService.installCommands("Linux", having(), false, false)).isEmpty();
        assertThat(FfmpegService.MANUAL).contains("brew install ffmpeg", "apt-get install -y ffmpeg");
    }

    @Test
    void withNoWayToInstallItTheInstallFailsWithTheManualCommands() throws Exception {
        ProviderProbe probe = new ProviderProbe(new CliPaths("/tmp/none"));
        FfmpegService none = new FfmpegService(new CliPaths("/tmp/none"), probe, Optional::empty, List::of);

        assertThat(none.installLog()).isNull();
        none.install();
        for (int i = 0; i < 100 && none.status().installing(); i++) {
            Thread.sleep(50);
        }

        var status = none.status();
        assertThat(status.installed()).isFalse();
        assertThat(status.hasLog()).isTrue();
        assertThat(status.error()).contains("找不到能自动安装的方式", "brew install ffmpeg", "apt-get install -y ffmpeg");
        assertThat(none.installLog().error()).isEqualTo(status.error());
        assertThat(none.installLog().lines()).last().asString().startsWith("✗ 失败");
    }

    @Test
    void theStatusSaysWhetherItIsThere() {
        ProviderProbe probe = new ProviderProbe(new CliPaths("/tmp/none")) {
            @Override public Optional<String> version(Path binary, List<String> args) { return Optional.of("7.1.1"); }
        };
        assertThat(new FfmpegService(new CliPaths("/tmp/none"), probe, Optional::empty, List::of).status().installed()).isFalse();
        var there = new FfmpegService(new CliPaths("/tmp/none"), probe, () -> Optional.of(Path.of("/opt/x/ffmpeg")), List::of).status();
        assertThat(there.installed()).isTrue();
        assertThat(there.version()).isEqualTo("7.1.1");
        assertThat(there.path()).isEqualTo("/opt/x/ffmpeg");
        assertThat(there.installing()).isFalse();
    }
}
