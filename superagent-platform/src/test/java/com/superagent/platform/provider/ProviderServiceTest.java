package com.superagent.platform.provider;

import com.superagent.api.provider.ProviderDto;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.provider.ModelProvider;
import com.superagent.core.provider.ModelProviderStore;
import com.superagent.core.provider.ProviderCatalog;
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
        FakeProbe(boolean installed, boolean authed) { this.installed = installed; this.authed = authed; }
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
        @Override public Result fetch(String key) throws IOException {
            if (error != null) {
                throw new IOException(error);
            }
            return new Result(models, "fake");
        }
    }

    /** Inference double: every model answers except those listed in {@code failing}. */
    private static final class FakeInference extends ProviderInferenceProbe {
        final Set<String> failing = new HashSet<>();
        FakeInference() { super(new FakeProbe(true, true)); }
        @Override public void check(String key, String modelId) throws IOException {
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
        return new ProviderService(store, probe, fetcher, inference);
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
    }

    @Test
    void displayOnlyEntriesCannotInstall() {
        ProviderService service = service(new InMemoryStore(), new FakeProbe(false, false), new FakeFetcher());
        ProviderDto glm = service.add("glm");
        assertThat(glm.supported()).isFalse();
        assertThat(glm.status()).isEqualTo("unsupported");
        assertThat(glm.models()).isEmpty();
        assertThatThrownBy(() -> service.install(glm.id())).isInstanceOf(IllegalArgumentException.class);
    }
}
