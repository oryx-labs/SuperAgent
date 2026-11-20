package com.superagent.platform.provider;

import com.superagent.api.provider.FfmpegDto;
import com.superagent.api.provider.InstallLogDto;
import com.superagent.runtime.cli.CliPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ffmpeg, a platform dependency: transcription converts and cuts audio with it, so files of any size and format can
 * be transcribed. The Models page shows it and installs it with the system's own package manager (Homebrew on macOS;
 * apt-get, dnf, yum or apk on Linux, as root or with password-less sudo) — never a downloaded script.
 */
@Service
public class FfmpegService {

    static final String MANUAL = "请在服务器上手动安装 ffmpeg：macOS 用 brew install ffmpeg；Debian/Ubuntu 用 sudo apt-get install -y ffmpeg；"
            + "RHEL/Fedora 用 sudo dnf install -y ffmpeg（可能要先启用 RPM Fusion）；Alpine 用 apk add ffmpeg。装好后刷新本页即可，不用重启";

    private final CliPaths paths;
    private final ProviderProbe probe;
    private final Supplier<Optional<Path>> locate;
    private final Supplier<List<List<String>>> commands; // what installs it here; empty: no way to
    private final ExecutorService installer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ffmpeg-installer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile InstallJob lastJob;

    @Autowired
    public FfmpegService(CliPaths paths, ProviderProbe probe) {
        this.paths = paths;
        this.probe = probe;
        this.locate = () -> paths.find("ffmpeg").or(() -> Optional.of(Path.of("/usr/bin/ffmpeg")).filter(Files::isExecutable));
        this.commands = this::commandsHere;
    }

    FfmpegService(CliPaths paths, ProviderProbe probe, Supplier<Optional<Path>> locate, Supplier<List<List<String>>> commands) {
        this.paths = paths;
        this.probe = probe;
        this.locate = locate;
        this.commands = commands;
    }

    /** Where the server's ffmpeg is, looked up each time, so one installed while the server runs counts at once. */
    public Optional<Path> find() {
        return locate.get();
    }

    public FfmpegDto status() {
        Optional<Path> bin = find();
        String version = bin.flatMap(b -> probe.version(b, List.of("-version"))).orElse(null);
        InstallJob job = lastJob;
        boolean installing = job != null && job.running();
        return new FfmpegDto(bin.isPresent(), version, bin.map(Path::toString).orElse(null), installing,
                lastError.get(), job == null ? null : job.currentStep(), job != null);
    }

    /** Starts installing in the background; the status reports progress and the log what it printed. */
    public synchronized FfmpegDto install() {
        if (lastJob != null && lastJob.running()) {
            return status();
        }
        InstallJob job = new InstallJob("install", "准备安装 ffmpeg");
        lastJob = job;
        lastError.set(null);
        installer.execute(() -> {
            String failure;
            try {
                failure = run(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "已中断";
            }
            lastError.set(failure);
            job.finish(failure);
        });
        return status();
    }

    public InstallLogDto installLog() {
        InstallJob job = lastJob;
        return job == null ? null : job.toDto();
    }

    private List<List<String>> commandsHere() {
        String os = System.getProperty("os.name", "");
        boolean root = "root".equals(System.getProperty("user.name"));
        Function<String, Optional<Path>> which = paths::find;
        return installCommands(os, which, root, !root && sudoWithoutPassword(which));
    }

    private String run(InstallJob job) throws InterruptedException {
        List<List<String>> commands = this.commands.get();
        if (commands.isEmpty()) {
            return "这台服务器上找不到能自动安装的方式（macOS 需要 Homebrew；Linux 需要 apt-get/dnf/yum/apk，并以 root 运行或有免密 sudo）。" + MANUAL;
        }
        for (List<String> argv : commands) {
            String failure = exec(argv, job);
            if (failure != null) {
                return failure + "。" + MANUAL;
            }
        }
        return find().isPresent() ? null : "安装命令跑完了，但仍找不到 ffmpeg。" + MANUAL;
    }

    /**
     * The commands that install ffmpeg here, in order; empty when there's no package manager it can use.
     * {@code sudo}: password-less sudo works (only asked when not root).
     */
    static List<List<String>> installCommands(String os, Function<String, Optional<Path>> which, boolean root, boolean sudo) {
        String name = os.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) {
            return which.apply("brew").map(b -> List.of(List.of(b.toString(), "install", "ffmpeg"))).orElse(List.of());
        }
        if (!name.contains("linux") || (!root && !sudo)) {
            return List.of();
        }
        List<String> prefix = root ? List.of() : List.of("sudo", "-n");
        Optional<Path> apt = which.apply("apt-get");
        if (apt.isPresent()) {
            return List.of(with(prefix, apt.get().toString(), "update"), with(prefix, apt.get().toString(), "install", "-y", "ffmpeg"));
        }
        for (String pm : List.of("dnf", "yum")) {
            Optional<Path> bin = which.apply(pm);
            if (bin.isPresent()) {
                return List.of(with(prefix, bin.get().toString(), "install", "-y", "ffmpeg"));
            }
        }
        return which.apply("apk").map(b -> List.of(with(prefix, b.toString(), "add", "--no-cache", "ffmpeg"))).orElse(List.of());
    }

    private static List<String> with(List<String> prefix, String... argv) {
        List<String> out = new ArrayList<>(prefix);
        out.addAll(List.of(argv));
        return out;
    }

    private static boolean sudoWithoutPassword(Function<String, Optional<Path>> which) {
        Optional<Path> sudo = which.apply("sudo");
        if (sudo.isEmpty()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(sudo.get().toString(), "-n", "true").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Runs one command, its output into the job; null when it exits 0, else why not. */
    private String exec(List<String> argv, InstallJob job) throws InterruptedException {
        job.step("运行 " + String.join(" ", argv));
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("PATH", String.join(File.pathSeparator, paths.searchPath()) + File.pathSeparator + "/usr/sbin:/usr/bin:/sbin:/bin");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        pb.environment().put("HOMEBREW_NO_AUTO_UPDATE", "1");
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return "启动 " + argv.get(0) + " 失败：" + e.getMessage();
        }
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    String clean = line.replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "").stripTrailing();
                    if (!clean.isBlank()) {
                        job.line(clean);
                    }
                }
            } catch (IOException ignored) {
                // the process is gone
            }
        });
        if (!proc.waitFor(30, TimeUnit.MINUTES)) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
            return "超过 30 分钟仍未完成，已停止";
        }
        reader.join(TimeUnit.SECONDS.toMillis(5));
        return proc.exitValue() == 0 ? null : String.join(" ", argv) + " 失败（退出码 " + proc.exitValue() + "）";
    }
}
