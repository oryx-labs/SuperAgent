package com.superagent.platform.provider;

import com.superagent.api.provider.CredentialDto;
import com.superagent.api.provider.CredentialRequests;
import com.superagent.core.chat.RunCredential;
import com.superagent.core.provider.ModelProvider;
import com.superagent.core.provider.ModelProviderStore;
import com.superagent.core.provider.ProviderCredential;
import com.superagent.core.provider.ProviderCredentialStore;
import com.superagent.core.security.SecretCipher;
import com.superagent.runtime.cli.CliPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CredentialServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void inCompany() {
        // The host's company: the only one that may fall back to the machine's own environment / CLI login.
        com.superagent.core.tenant.TenantContext.set(com.superagent.core.tenant.Company.DEFAULT_ID);
    }

    @org.junit.jupiter.api.AfterEach
    void leaveCompany() {
        com.superagent.core.tenant.TenantContext.clear();
    }

    private static final class InMemoryPool implements ProviderCredentialStore {
        final Map<String, ProviderCredential> rows = new LinkedHashMap<>();
        @Override public List<ProviderCredential> list(String key) {
            return rows.values().stream().filter(c -> c.providerKey().equals(key))
                    .sorted(Comparator.comparingInt(ProviderCredential::priority)).toList();
        }
        @Override public Optional<ProviderCredential> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public void save(ProviderCredential c) {
            rows.put(c.id(), c);
            if (c.keyHash() != null) {
                hashes.add(c.keyHash());
            }
        }
        final Set<String> hashes = new HashSet<>();
        @Override public void delete(String id) { rows.remove(id); }
        @Override public Set<String> knownKeyHashes(String key) { return new HashSet<>(hashes); }
    }

    /** Keys listed in {@code down} fail their check (e.g. quota exhausted); the rest answer. */
    private static final class FakeProbe extends ProviderInferenceProbe {
        final Set<String> down = new HashSet<>();
        FakeProbe(Path tmp) { super(new ProviderProbe(new CliPaths(tmp.resolve("node").toString()))); }
        @Override public void check(String key, String model, RunCredential c) throws IOException {
            if (c != null && down.contains(c.apiKey())) {
                throw new IOException("HTTP 429: quota exceeded");
            }
        }
        @Override public void checkKey(String key, String model, RunCredential c) throws IOException {
            check(key, model, c);
        }
    }

    @TempDir
    Path tmp;

    @Test
    void runsUseTheBestHealthyKeyFailOverOnAnEndpointErrorAndReturnOnceItRecovers() {
        InMemoryPool pool = new InMemoryPool();
        ModelProviderStore providers = mock(ModelProviderStore.class);
        when(providers.findByKey(anyString())).thenReturn(Optional.of(new ModelProvider("p", "claude", true, true,
                null, null, "ok", null, "[{\"id\":\"claude-x\",\"inferStatus\":\"ok\"}]", null, null, Instant.now(), Instant.now())));
        FakeProbe probe = new FakeProbe(tmp);
        CredentialService svc = new CredentialService(pool, providers,
                new SecretCipher("", tmp.resolve("secret.key").toString()), probe, null);

        // Every company runs on its own keys only, never the machine's: without one it is told to add one.
        assertThatThrownBy(() -> svc.current("claude")).isInstanceOf(CredentialService.NoCompanyKeyException.class);
        assertThatThrownBy(() -> com.superagent.core.tenant.TenantContext.call("acme", () -> svc.current("claude")))
                .isInstanceOf(CredentialService.NoCompanyKeyException.class);
        assertThat(svc.list("claude")).isEmpty(); // the key page still opens, to add the first one
        String main = svc.add("claude", new CredentialRequests.Save("官方", "", "sk-ant-main-0000000001", false, null)).id();
        String relay = svc.add("claude", new CredentialRequests.Save("中转", "https://relay.example/", "relay-token-000000002", true, null)).id();
        assertThat(svc.current("claude").credential().apiKey()).isEqualTo("sk-ant-main-0000000001");
        // No endpoint of its own: sent to the official address explicitly, never left to the server's CLI config.
        assertThat(svc.current("claude").credential().baseUrl()).isEqualTo("https://api.anthropic.com");

        // The page sees masked keys, and only the company's own.
        List<CredentialDto> listed = svc.list("claude");
        assertThat(listed).extracting(CredentialDto::keyHint).first().isEqualTo("sk-a…0001");
        assertThat(listed.get(1).baseUrl()).isEqualTo("https://relay.example");
        assertThat(listed).hasSize(2);

        // A quota error is the key's fault: mark it, retry on the relay. A bad request is not: no switch.
        assertThat(svc.failover("claude", svc.current("claude"), "API Error: 400 prompt is too long")).isEmpty();
        CredentialService.Choice next = svc.failover("claude", svc.current("claude"), "API Error: 429 rate_limit_error").orElseThrow();
        assertThat(next.id()).isEqualTo(relay);
        assertThat(svc.current("claude").id()).isEqualTo(relay);

        // The relay fails too: nothing healthy left, so it stays on the company's best key (never the machine's).
        assertThat(svc.failover("claude", next, "connect ECONNREFUSED").orElseThrow().id()).isEqualTo(main);

        // The periodic check sees the main key answer again (and the relay still down): back to priority order.
        probe.down.add("relay-token-000000002");
        svc.checkAll();
        assertThat(svc.current("claude").id()).isEqualTo(main);
        assertThat(pool.find(relay).orElseThrow().status()).isEqualTo(ProviderCredential.ERROR);
    }

    @Test
    void onlyErrorsAnotherKeyCouldFixCountAsEndpointFailures() {
        assertThat(EndpointFailures.isEndpointFailure("Failed to authenticate: OAuth session expired and could not be refreshed")).isTrue();
        assertThat(EndpointFailures.isEndpointFailure("API Error: 529 {\"type\":\"overloaded_error\"}")).isTrue();
        assertThat(EndpointFailures.isEndpointFailure("unexpected status 401 Unauthorized")).isTrue();
        assertThat(EndpointFailures.isEndpointFailure("API Error: 404 model: claude-nope not found")).isFalse();
        assertThat(EndpointFailures.isEndpointFailure("Bash failed: line 500 of build.log")).isFalse();
    }

    @Test
    void theMachinesKeysAreImportedOnceFirstIntoAnEmptyPoolAndADeletedOneStaysDeleted() {
        InMemoryPool pool = new InMemoryPool();
        ModelProviderStore providers = mock(ModelProviderStore.class);
        when(providers.findByKey(anyString())).thenReturn(Optional.empty());
        MachineCredentials machine = new MachineCredentials(
                Map.of("ANTHROPIC_API_KEY", "sk-ant-from-env-000001", "ANTHROPIC_BASE_URL", "https://gw.example")::get,
                tmp.resolve("claude"), tmp.resolve("codex"));
        CredentialService svc = new CredentialService(pool, providers,
                new SecretCipher("", tmp.resolve("secret.key").toString()), new FakeProbe(tmp), machine);

        assertThat(svc.importMachine("claude")).isEqualTo(1);
        CredentialDto imported = svc.list("claude").get(0);
        assertThat(imported.source()).isEqualTo(ProviderCredential.MACHINE);
        assertThat(imported.baseUrl()).isEqualTo("https://gw.example");
        assertThat(imported.current()).isTrue();             // runs keep using what they used before
        assertThat(svc.importMachine("claude")).isZero();    // found again on the next startup: no duplicate

        // Adding the same key by hand is refused; deleting the imported one keeps it out for good.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.add("claude",
                new CredentialRequests.Save("dup", "", "sk-ant-from-env-000001", false, null)))
                .isInstanceOf(IllegalArgumentException.class);
        svc.delete("claude", imported.id());
        assertThat(svc.importMachine("claude")).isZero();
    }
}
