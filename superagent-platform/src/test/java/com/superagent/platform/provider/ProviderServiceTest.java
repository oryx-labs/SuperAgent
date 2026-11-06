package com.superagent.platform.provider;

import com.superagent.api.provider.ProviderDto;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.provider.ModelProvider;
import com.superagent.core.provider.ModelProviderStore;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.core.chat.RunCredential;
import com.superagent.runtime.cli.CliPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderServiceTest {

    private static final class InMemoryStore implements ModelProviderStore {
        private final Map<String, ModelProvider> rows = new LinkedHashMap<>();
        @Override public List<ModelProvider> list() { return new ArrayList<>(rows.values()); }
        @Override public Optional<ModelProvider> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Optional<ModelProvider> findByKey(String key) {
            return rows.values().stream().filter(p -> p.providerKey().equals(key)).findFirst();
        }
        @Override public ModelProvider save(ModelProvider p) { rows.put(p.id(), p); return p; }
        @Override public void delete(String id) { rows.remove(id); }
    }

    /** Probe double: pretends the CLI is installed at a fixed version, with configurable auth. */
    private static final class FakeProbe extends ProviderProbe {
        private final boolean installed;
        private final boolean authed;
        FakeProbe(boolean installed, boolean authed) {
            super(new CliPaths(System.getProperty("java.io.tmpdir") + "/sa-test-node"));
            this.installed = installed;
            this.authed = authed;
        }
        @Override public Optional<Path> findBinary(String cli) {
            return installed ? Optional.of(Path.of("/bin/" + cli)) : Optional.empty();
        }
        @Override public Optional<String> version(Path binary, List<String> args) { return Optional.of("1.2.3"); }
        @Override public boolean looksAuthenticated(ProviderCatalog.Entry e) { return authed; }
        @Override public Optional<String> latestNpmVersion(String pkg) { return Optional.of("1.3.0"); }
    }

    /** Fetcher double: returns a fixed list, or fails when {@code error} is set. */
    private static final class FakeFetcher extends ProviderModelFetcher {
        List<ProviderModelDto> models = List.of(
                new ProviderModelDto("gpt-6-sol", "GPT-6 Sol", List.of("low", "medium", "high"), "medium", "model", 400000, true));
        String error;
        FakeFetcher() { super(new FakeProbe(true, true)); }
        @Override public Result fetch(String key, RunCredential credential) throws IOException {
            if (error != null) {
                throw new IOException(error);
            }
            return new Result(models, "fake");
        }
    }

    /** Node double: a fixed version on this "machine" (null = none). */
    private static final class FakeNode extends NodeRuntimeService {
        final String version;
        FakeNode(ProviderProbe probe, String version) {
            super(new CliPaths(System.getProperty("java.io.tmpdir") + "/sa-test-node"), probe);
            this.version = version;
        }
        @Override public Optional<String> version() { return Optional.ofNullable(version).map(v -> v.replace("v", "")); }
        /** The automatic dependency install, as if the server can't reach nodejs.org. */
        @Override String ensure(String minNode, InstallJob job) {
            job.step("安装 Node.js");
            return "自动安装 Node.js 失败：无法连接 nodejs.org";
        }
    }

    /** Inference double: every model answers except those listed in {@code failing}. */
    private static final class FakeInference extends ProviderInferenceProbe {
        final Set<String> failing = new HashSet<>();
        boolean offline;
        FakeInference() { super(new FakeProbe(true, true)); }
        @Override public void check(String key, String modelId, RunCredential credential) throws IOException {
            if (offline) {
                throw new ProbeUnreachableException("无法连接 https://gateway（ConnectException）");
            }
            if (failing.contains(modelId)) {
                throw new IOException("HTTP 404: model not found");
            }
        }
    }

    private static ProviderService service(ModelProviderStore store, ProviderProbe probe, FakeFetcher fetcher) {
        return service(store, probe, fetcher, new FakeInference());
    }

    private static ProviderService service(ModelProviderStore store, ProviderProbe probe, FakeFetcher fetcher,
                                           FakeInference inference) {
        return new ProviderService(store, probe, fetcher, inference, new FakeNode(probe, "v24.11.0"), null);
    }

    @Test
    void semverComparison() {
        assertThat(ProviderProbe.isNewer("2.1.10", "2.1.9")).isTrue();
        assertThat(ProviderProbe.isNewer("2.1.9", "2.1.9")).isFalse();
        assertThat(ProviderProbe.isNewer(null, "1.0.0")).isFalse();
        assertThat(ProviderProbe.semver("2.1.317 (Claude Code)")).contains("2.1.317");
        assertThat(ProviderProbe.semver("codex-cli 0.154.0")).contains("0.154.0");
    }

    @Test
    void addProbesStatusAndRejectsDuplicates() {
        ProviderService service = service(new InMemoryStore(), new FakeProbe(true, true), new FakeFetcher());

        ProviderDto claude = service.add("claude");
        assertThat(claude.installed()).isTrue();
        assertThat(claude.canInfer()).isTrue();
        assertThat(claude.currentVersion()).isEqualTo("1.2.3");
        assertThat(claude.status()).isEqualTo("ok");
        assertThat(service.catalog()).anySatisfy(c -> {
            assertThat(c.key()).isEqualTo("claude");
            assertThat(c.added()).isTrue();
        });

        assertThatThrownBy(() -> service.add("claude")).isInstanceOf(ProviderAlreadyAddedException.class);
    }

    @Test
    void statusReflectsAuthAndUpgrade() {
        ProviderService noAuth = service(new InMemoryStore(), new FakeProbe(true, false), new FakeFetcher());
        assertThat(noAuth.add("codex").status()).isEqualTo("no_auth");

        InMemoryStore store = new InMemoryStore();
        ProviderService authed = service(store, new FakeProbe(true, true), new FakeFetcher());
        ProviderDto p = authed.add("claude");
        ModelProvider row = store.findById(p.id()).orElseThrow();
        authed.refreshLatest(row);
        ModelProvider after = authed.refreshLocal(row);
        assertThat(after.latestVersion()).isEqualTo("1.3.0");
        assertThat(after.status()).isEqualTo("needs_upgrade");
    }

    @Test
    void modelListIsStoredAndAFailedRefreshKeepsTheLastGoodList() {
        InMemoryStore store = new InMemoryStore();
        FakeFetcher fetcher = new FakeFetcher();
        ProviderService service = service(store, new FakeProbe(true, true), fetcher);
        String id = service.add("codex").id();

        ProviderDto fetched = service.refreshModelsNow(id);
        assertThat(fetched.models()).extracting(ProviderModelDto::id).containsExactly("gpt-6-sol");
        assertThat(fetched.models().get(0).efforts()).containsExactly("low", "medium", "high");
        assertThat(fetched.modelsError()).isNull();

        fetcher.error = "codex is not installed";
        ProviderDto failed = service.refreshModelsNow(id);
        assertThat(failed.modelsError()).isEqualTo("codex is not installed");
        assertThat(failed.models()).extracting(ProviderModelDto::id).containsExactly("gpt-6-sol");
    }

    @Test
    void inferenceCheckIsRecordedPerModelAndSurvivesAListRefresh() {
        InMemoryStore store = new InMemoryStore();
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.models = List.of(
                new ProviderModelDto("claude-good", null, List.of(), null, "none", null, true),
                new ProviderModelDto("claude-listed-only", null, List.of(), null, "none", null, true));
        FakeInference inference = new FakeInference();
        inference.failing.add("claude-listed-only");
        ProviderService service = service(store, new FakeProbe(true, true), fetcher, inference);
        String id = service.add("claude").id();
        service.refreshModelsNow(id);

        service.refreshInference(store.findById(id).orElseThrow());
        assertThat(service.canInfer("claude", "claude-good")).isTrue();
        assertThat(service.canInfer("claude", "claude-listed-only")).isFalse();
        assertThat(service.detail(id).models()).filteredOn(m -> m.id().equals("claude-listed-only"))
                .singleElement().satisfies(m -> {
                    assertThat(m.inferStatus()).isEqualTo("error");
                    assertThat(m.inferError()).contains("404");
                });

        // Re-listing models must not wipe the probe results until the next sweep.
        service.refreshModelsNow(id);
        assertThat(service.canInfer("claude", "claude-good")).isTrue();

        // A sweep that can't reach the gateway is no verdict on the models: they keep their status, and the
        // caller is told to retry soon.
        inference.offline = true;
        assertThat(service.refreshInference(store.findById(id).orElseThrow())).isFalse();
        assertThat(service.canInfer("claude", "claude-good")).isTrue();
        assertThat(service.canInfer("claude", "claude-listed-only")).isFalse();
        inference.offline = false;
        assertThat(service.refreshInference(store.findById(id).orElseThrow())).isTrue();
    }

    @Test
    void installOnlyClisTrackInstallAndVersionButNeedARecentEnoughNode() throws InterruptedException {
        InMemoryStore store = new InMemoryStore();
        FakeProbe probe = new FakeProbe(true, false);
        ProviderService service = new ProviderService(store, probe, new FakeFetcher(), new FakeInference(),
                new FakeNode(probe, "v20.11.0"), null);
        ProviderDto minimax = service.add("minimax");
        assertThat(minimax.installable()).isTrue();
        assertThat(minimax.supported()).isFalse();
        assertThat(minimax.status()).isEqualTo("installed"); // no auth to check: nothing runs on it
        assertThat(minimax.models()).isEmpty();

        // MiniMax Code needs Node ≥ 22.19: its install first installs Node; when that fails the row says why
        // and the CLI install isn't attempted.
        assertThat(service.install(minimax.id()).status()).isEqualTo("installing");
        ProviderDto after = service.detail(minimax.id());
        for (int i = 0; i < 100 && "installing".equals(after.status()); i++) {
            Thread.sleep(20);
            after = service.detail(minimax.id());
        }
        assertThat(after.status()).isNotEqualTo("installing");
        assertThat(after.message()).contains("Node.js").contains("nodejs.org");
        // The page can show what happened, step by step, not just a status that stopped changing.
        assertThat(after.hasInstallLog()).isTrue();
        var installLog = service.installLog(minimax.id());
        assertThat(installLog.running()).isFalse();
        assertThat(installLog.error()).contains("nodejs.org");
        assertThat(installLog.lines()).anyMatch(l -> l.contains("安装 Node.js")).last().asString().startsWith("✗");
        assertThat(after.installCommand()).isEqualTo("npm install -g @minimax-ai/code@latest --no-fund --no-audit");
    }
}
