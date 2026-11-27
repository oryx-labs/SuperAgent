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

    @Test
    void theSweepChecksAWorkingModelDailyAndBacksOffAFailingOne() {
        java.time.Instant t = java.time.Instant.parse("2026-11-27T00:00:00Z");
        ProviderModelDto fresh = new ProviderModelDto("m", "m", List.of(), null, "none", null, true);
        assertThat(ProviderService.dueForSweep(fresh, t)).isTrue();               // never checked: at once

        ProviderModelDto ok = fresh.checked(true, null, t);
        assertThat(ProviderService.dueForSweep(ok, t.plus(java.time.Duration.ofHours(12)))).isFalse();
        assertThat(ProviderService.dueForSweep(ok, t.plus(java.time.Duration.ofHours(24)))).isTrue();

        ProviderModelDto failed = fresh.checked(false, "boom", t);                  // 1st failure: an hour
        assertThat(ProviderService.dueForSweep(failed, t.plus(java.time.Duration.ofHours(1)))).isTrue();
        ProviderModelDto thrice = failed.checked(false, "boom", t).checked(false, "boom", t);   // 3rd: four hours
        assertThat(thrice.inferFailures()).isEqualTo(3);
        assertThat(ProviderService.dueForSweep(thrice, t.plus(java.time.Duration.ofHours(2)))).isFalse();
        assertThat(ProviderService.dueForSweep(thrice, t.plus(java.time.Duration.ofHours(4)))).isTrue();
        ProviderModelDto many = fresh;
        for (int i = 0; i < 20; i++) {
            many = many.checked(false, "boom", t);
        }
        assertThat(ProviderService.dueForSweep(many, t.plus(java.time.Duration.ofHours(24)))).isTrue();   // capped at a day

        assertThat(thrice.checked(true, null, t).inferFailures()).isZero();       // one success clears the count
    }

    @org.junit.jupiter.api.BeforeEach
    void inCompany() {
        com.superagent.core.tenant.TenantContext.set("acme"); // work handed to other threads carries the company
    }

    @org.junit.jupiter.api.AfterEach
    void leaveCompany() {
        com.superagent.core.tenant.TenantContext.clear();
    }

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
        boolean claudeAuthed;
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
        @Override public boolean codexLoggedIn() { return authed; }
        @Override public boolean claudeLoggedIn() { return claudeAuthed; }
        @Override public Optional<String> latestNpmVersion(String pkg) { return Optional.of("1.3.0"); }
    }

    /** Fetcher double: returns a fixed list, or fails when {@code error} is set. */
    private static final class FakeFetcher extends ProviderModelFetcher {
        List<ProviderModelDto> models = List.of(
                new ProviderModelDto("gpt-6-sol", "GPT-6 Sol", List.of("low", "medium", "high"), "medium", "model", 400000, true));
        String error;
        FakeFetcher() { super(new FakeProbe(true, true), new MachineCredentials(Map.<String, String>of()::get,
                Path.of(System.getProperty("java.io.tmpdir"), "sa-test-claude"),
                Path.of(System.getProperty("java.io.tmpdir"), "sa-test-codex"))); }
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
        int calls;
        FakeInference() { super(new FakeProbe(true, true)); }
        @Override public void check(String key, String modelId, RunCredential credential) throws IOException {
            calls++;
            if (offline) {
                throw new ProbeUnreachableException("无法连接 https://gateway（ConnectException）");
            }
            if (failing.contains(modelId)) {
                throw new IOException("HTTP 404: model not found");
            }
        }
    }

    /** Waits until no round of model checks is running for the provider (they run in the background). */
    private static void idle(ProviderService service, String id) {
        for (int i = 0; i < 200; i++) {
            if (service.detail(id).checkingSince() == null && service.detail(id).models().stream().allMatch(m -> m.inferStatus() != null)) {
                return;
            }
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
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
    void enabledCodexLoginUpdatesStatusAndAllowsModelChecksWithoutCompanyKey() {
        InMemoryStore store = new InMemoryStore();
        FakeProbe probe = new FakeProbe(true, true);
        FakeInference inference = new FakeInference();
        CredentialService credentials = org.mockito.Mockito.mock(CredentialService.class);
        org.mockito.Mockito.when(credentials.allowsCliLogin("codex")).thenReturn(true);
        org.mockito.Mockito.when(credentials.canChoose("codex")).thenReturn(true);
        org.mockito.Mockito.when(credentials.current("codex")).thenReturn(new CredentialService.Choice(null));
        ProviderService service = new ProviderService(store, probe, new FakeFetcher(), inference,
                new FakeNode(probe, "v24.11.0"), credentials);
        ModelProvider p = new ModelProvider("codex-test", "codex", false, false, null, null,
                "no_auth", null, null, null, null, java.time.Instant.now(), java.time.Instant.now());
        store.save(p);
        assertThat(service.refreshLocal(p).canInfer()).isTrue();
        ModelProvider fetched = service.refreshModels(p);
        assertThat(fetched.modelsJson()).contains("gpt-6-sol");
        service.refreshInference(fetched, true);
        assertThat(inference.calls).isGreaterThan(0);
    }

    @Test
    void enabledClaudeLoginAsksClaudeNotCodex() {
        InMemoryStore store = new InMemoryStore();
        FakeProbe probe = new FakeProbe(true, false); // codex not logged in
        probe.claudeAuthed = true;
        CredentialService credentials = org.mockito.Mockito.mock(CredentialService.class);
        org.mockito.Mockito.when(credentials.allowsCliLogin("claude")).thenReturn(true);
        ProviderService service = new ProviderService(store, probe, new FakeFetcher(), new FakeInference(),
                new FakeNode(probe, "v24.11.0"), credentials);
        ModelProvider p = new ModelProvider("claude-test", "claude", false, false, null, null,
                "no_auth", null, null, null, null, java.time.Instant.now(), java.time.Instant.now());
        store.save(p);
        assertThat(service.refreshLocal(p).status()).isNotEqualTo("no_auth");
        probe.claudeAuthed = false;
        assertThat(service.refreshLocal(p).status()).isEqualTo("no_auth");
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

        // Adding it and refreshing the list each start a round in the background; another round meanwhile isn't
        // started twice. Wait for it.
        service.refreshInference(store.findById(id).orElseThrow());
        idle(service, id);
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
        idle(service, id); // the list refresh above started a round of its own
        inference.offline = true;
        assertThat(service.refreshInference(store.findById(id).orElseThrow(), true)).isFalse();
        assertThat(service.canInfer("claude", "claude-good")).isTrue();
        assertThat(service.canInfer("claude", "claude-listed-only")).isFalse();
        inference.offline = false;
        assertThat(service.refreshInference(store.findById(id).orElseThrow())).isTrue();

        // The scheduled sweep leaves models checked within the hour alone (a restart doesn't re-check them all).
        int calls = inference.calls;
        assertThat(service.refreshInference(store.findById(id).orElseThrow())).isTrue();
        assertThat(inference.calls).isEqualTo(calls);
    }

    @Test
    void realRunsAndNewModelsDecideChecksInsteadOfTheClock() {
        InMemoryStore store = new InMemoryStore();
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.models = List.of(new ProviderModelDto("claude-a", null, List.of(), null, "none", null, true));
        FakeInference inference = new FakeInference();
        inference.failing.add("claude-a");
        ProviderService service = service(store, new FakeProbe(true, true), fetcher, inference);
        String id = service.add("claude").id();
        service.refreshModelsNow(id);
        idle(service, id);
        assertThat(service.canInfer("claude", "claude-a")).isFalse();

        // A real run that got an answer is the check: the model can run again, without a probe.
        int calls = inference.calls;
        service.answered("claude", "claude-a");
        assertThat(service.canInfer("claude", "claude-a")).isTrue();
        assertThat(inference.calls).isEqualTo(calls);

        // A failed real run doesn't mark it down by itself; it checks the model, and a model checked moments ago
        // isn't checked again (failures come in bursts).
        service.verifySoon("claude", "claude-a");
        idle(service, id);
        assertThat(inference.calls).isEqualTo(calls);
        assertThat(service.canInfer("claude", "claude-a")).isTrue();

        // A model that appears on the list is checked right away, the others are left alone.
        fetcher.models = List.of(new ProviderModelDto("claude-a", null, List.of(), null, "none", null, true),
                new ProviderModelDto("claude-b", null, List.of(), null, "none", null, true));
        service.refreshModelsNow(id);
        idle(service, id);
        assertThat(service.canInfer("claude", "claude-b")).isTrue();
        assertThat(inference.calls).isEqualTo(calls + 1);
    }

    @Test
    void minimaxInstallRequiresRecentEnoughNodeEvenWhenSupported() throws InterruptedException {
        InMemoryStore store = new InMemoryStore();
        FakeProbe probe = new FakeProbe(true, false);
        ProviderService service = new ProviderService(store, probe, new FakeFetcher(), new FakeInference(),
                new FakeNode(probe, "v20.11.0"), null);
        ProviderDto minimax = service.add("minimax");
        assertThat(minimax.installable()).isTrue();
        assertThat(minimax.supported()).isTrue();
        assertThat(minimax.status()).isEqualTo("no_auth"); // supported, installed in fake probe, but no key yet
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
