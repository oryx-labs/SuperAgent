package com.superagent.platform.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.api.provider.NodeRuntimeDto;
import com.superagent.runtime.cli.CliPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The Node.js the npm-based CLIs need. When the server has none (or one older than a CLI requires), the Models
 * page installs a private copy: the current LTS from nodejs.org, checked against the release's official
 * SHA256SUMS before it is unpacked into SuperAgent's own data directory — no sudo, nothing outside that
 * directory changes. {@link CliPaths} puts it first on the search path, so npm installs land beside it and the
 * runners find them.
 */
@Service
public class NodeRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(NodeRuntimeService.class);
    private static final String DIST = "https://nodejs.org/dist/";

    private final CliPaths paths;
    private final ProviderProbe probe;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService installer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "node-installer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean installing = new AtomicBoolean();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    /** The last private-Node install, from the Node card or as a CLI install's first step. */
    private volatile InstallJob lastJob;

    public NodeRuntimeService(CliPaths paths, ProviderProbe probe) {
        this.paths = paths;
        this.probe = probe;
    }

    /**
     * For an npm that belongs to the private Node: the prefix its global installs must go to (the Node's own
     * directory, whose bin is on the search path). Empty for a system npm, which keeps its own config.
     */
    public Optional<Path> privatePrefixFor(Path npmBinary) {
        Path home = paths.nodeRoot().resolve("current");
        return npmBinary.toAbsolutePath().normalize().startsWith(home) ? Optional.of(home) : Optional.empty();
    }

    /** The Node a CLI install would use (the first {@code node} on the search path), and its version. */
    public Optional<String> version() {
        return paths.find("node").flatMap(bin -> probe.version(bin, List.of("--version")));
    }

    public NodeRuntimeDto status() {
        Optional<Path> bin = paths.find("node");
        String version = bin.flatMap(b -> probe.version(b, List.of("--version"))).orElse(null);
        String source = bin.isEmpty() ? null : bin.get().startsWith(paths.nodeRoot()) ? "private" : "system";
        InstallJob job = lastJob;
        return new NodeRuntimeDto(version, source, bin.map(Path::toString).orElse(null), installing.get(),
                lastError.get(), job == null ? null : job.currentStep(), job != null);
    }

    /** Starts installing (or replacing) the private Node in the background; the status reports progress. */
    public NodeRuntimeDto install() {
        InstallJob job = new InstallJob("install", "排队中：等前一个 Node.js 安装结束");
        lastJob = job;
        installer.execute(() -> {
            try {
                installPrivate(job);
                job.finish(null);
            } catch (IOException e) {
                job.finish(e.getMessage()); // also in lastError for the status
            } catch (InterruptedException e) {
                job.finish("已中断");
                Thread.currentThread().interrupt();
            }
        });
        return status();
    }

    /** The log of the last private-Node install, or null when there's been none since the server started. */
    public com.superagent.api.provider.InstallLogDto installLog() {
        InstallJob job = lastJob;
        return job == null ? null : job.toDto();
    }

    /**
     * Makes sure a Node of at least {@code minNode} is usable, installing the private LTS when there is none or
     * it's too old (a CLI install's dependency). Blocking; null when ready, else why not.
     */
    String ensure(String minNode, InstallJob job) throws InterruptedException {
        Optional<String> have = version();
        if (have.isPresent() && !ProviderProbe.isNewer(minNode, have.get()) && paths.find("npm").isPresent()) {
            return null;
        }
        job.step("安装 Node.js（需要 ≥ " + minNode + "，当前 " + have.orElse("没有") + "）");
        try {
            installPrivate(job);
        } catch (IOException e) {
            return "自动安装 Node.js 失败：" + e.getMessage();
        }
        have = version();
        return have.isPresent() && !ProviderProbe.isNewer(minNode, have.get()) ? null
                : "需要 Node.js ≥ " + minNode + "，自动安装后仍不满足（当前 " + have.orElse("无") + "）";
    }

    /** Installs the private Node now (blocking); null when done, else why it failed. */
    String installPrivateNow(InstallJob job) throws InterruptedException {
        try {
            installPrivate(job);
            return null;
        } catch (IOException e) {
            return "自动安装 Node.js 失败：" + e.getMessage();
        }
    }

    /** Whether the private Node (with its npm) is already installed. */
    public boolean hasPrivate() {
        return Files.isExecutable(paths.privateNodeBin().resolve("npm"));
    }

    /** One install at a time; a caller arriving mid-install waits for it instead of starting another. */
    private synchronized void installPrivate(InstallJob job) throws IOException, InterruptedException {
        installing.set(true);
        lastError.set(null);
        lastJob = job;
        try {
            String installed = installLatestLts(job);
            log.info("private Node {} installed under {}", installed, paths.nodeRoot());
        } catch (IOException | RuntimeException e) {
            log.warn("private Node install failed: {}", e.getMessage());
            lastError.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        } finally {
            installing.set(false);
        }
    }

    private String installLatestLts(InstallJob job) throws IOException, InterruptedException {
        job.step("查询 Node.js 最新 LTS 版本（" + DIST + "index.json）");
        String version = latestLts();
        String platform = platform();
        String dirName = "node-" + version + "-" + platform;
        String file = dirName + ".tar.gz";
        Path root = paths.nodeRoot();
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".staging-");
        try {
            Path archive = staging.resolve(file);
            job.step("下载 " + DIST + version + "/" + file);
            download(DIST + version + "/" + file, archive, job);
            job.step("校验 SHA-256");
            verify(archive, expectedSha256(version, file));
            job.step("解压到 " + root.resolve("current"));
            run(List.of("tar", "-xzf", archive.toString(), "-C", staging.toString()));
            Path unpacked = staging.resolve(dirName);
            if (!Files.isExecutable(unpacked.resolve("bin").resolve("node"))) {
                throw new IOException("Node archive has no bin/node");
            }
            Path current = root.resolve("current");
            deleteTree(current);
            Files.move(unpacked, current);
            return version;
        } finally {
            deleteTree(staging);
        }
    }

    /** The newest LTS release in nodejs.org's own index (it lists newest first). */
    private String latestLts() throws IOException, InterruptedException {
        JsonNode index = mapper.readTree(get(DIST + "index.json"));
        for (JsonNode release : index) {
            if (release.path("lts").isTextual()) {
                return release.path("version").asText();
            }
        }
        throw new IOException("No LTS release in the Node.js index");
    }

    private String expectedSha256(String version, String file) throws IOException, InterruptedException {
        for (String line : get(DIST + version + "/SHASUMS256.txt").split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equals(file)) {
                return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        throw new IOException("No checksum published for " + file);
    }

    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.contains("mac") ? "darwin" : os.contains("linux") ? "linux" : null;
        String a = arch.equals("amd64") || arch.equals("x86_64") ? "x64"
                : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : null;
        if (o == null || a == null) {
            throw new IllegalStateException("自动安装 Node.js 只支持 macOS / Linux 的 x64 或 arm64（当前：" + os + " " + arch + "）");
        }
        return o + "-" + a;
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("GET " + url + " → HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /**
     * Streams the archive to disk, logging progress every tenth. HttpClient has no read timeout, so a watchdog
     * gives up when no bytes arrive for {@link #STALL}: a dead connection otherwise hangs the install for good.
     */
    private void download(String url, Path target, InstallJob job) throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("下载 " + url + " 失败：HTTP " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
        java.util.concurrent.atomic.AtomicLong lastByteAt = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        InputStream in = resp.body();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(5_000);
                    if (System.nanoTime() - lastByteAt.get() > STALL.toNanos()) {
                        stalled.set(true);
                        in.close(); // unblocks the read below
                        return;
                    }
                }
            } catch (InterruptedException | IOException ignored) {
                // download finished
            }
        });
        try (in; var out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int reported = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                lastByteAt.set(System.nanoTime());
                int tenth = total > 0 ? (int) (done * 10 / total) : (int) (done / (10L << 20));
                if (tenth > reported) {
                    reported = tenth;
                    job.line("已下载 " + mb(done) + (total > 0 ? " / " + mb(total) : ""));
                }
            }
            if (total > 0 && done < total) {
                throw new IOException("下载中断（" + mb(done) + " / " + mb(total) + "）");
            }
        } catch (IOException e) {
            if (stalled.get()) {
                throw new IOException("下载 " + url + " 超过 " + STALL.toSeconds() + " 秒没有收到数据，已放弃。请检查服务器能否访问 nodejs.org");
            }
            throw e;
        } finally {
            watchdog.interrupt();
        }
    }

    private static final Duration STALL = Duration.ofSeconds(60);

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** The archive must match nodejs.org's published checksum, or nothing is unpacked. */
    static void verify(Path file, String expected) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                sha.update(buf, 0, n);
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equals(expected)) {
                throw new IOException("Node.js 安装包校验失败（SHA-256 不一致），已放弃安装");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void run(List<String> argv) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
            throw new IOException(argv.get(0) + " failed: " + out.strip());
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
