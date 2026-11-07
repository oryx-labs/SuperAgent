package com.superagent.platform.settings;

import com.superagent.api.settings.SystemSettingsDto;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.provider.ProviderService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsServiceTest {

    private static final class InMemoryStore implements SystemSettingStore {
        private final Map<String, String> rows = new LinkedHashMap<>();
        @Override public Map<String, String> all() { return new LinkedHashMap<>(rows); }
        @Override public void put(String key, String value) { rows.put(key, value == null ? "" : value); }
    }

    /** Provider double: only the given models passed the inference check, under codex if prefixed "gpt". */
    private static final class FakeProviders extends ProviderService {
        private final Set<String> healthy;
        FakeProviders(String... healthy) { super(null, null, null, null, null, null); this.healthy = Set.of(healthy); }
        @Override public Optional<String> cliFor(String modelId) {
            return healthy.contains(modelId)
                    ? Optional.of(modelId.startsWith("gpt") ? "codex" : "claude") : Optional.empty();
        }
    }

    private final InMemoryStore store = new InMemoryStore();
    private final SettingsService service = new SettingsService(store, new FakeProviders("claude-opus-5-5", "gpt-5.5"));

    @Test
    void unsetByDefault() {
        assertThat(service.get().agentDefaultModel()).isNull();
        assertThat(service.agentDefaultModel()).isNull();
    }

    @Test
    void savesTrimmedValuesAndBlankClearsThem() {
        assertThat(service.update(new SystemSettingsDto("  claude-opus-5-5 ", null, null)).agentDefaultModel())
                .isEqualTo("claude-opus-5-5");
        assertThat(service.agentDefaultModel()).isEqualTo("claude-opus-5-5");
        assertThat(service.update(new SystemSettingsDto("gpt-5.5", null, null)).agentDefaultModel()).isEqualTo("gpt-5.5");

        assertThat(service.update(new SystemSettingsDto(" ", null, null)).agentDefaultModel()).isNull();
        assertThat(service.update(new SystemSettingsDto(null, " 超级智能科技有限公司 ", "超智"))).satisfies(d -> {
            assertThat(d.companyName()).isEqualTo("超级智能科技有限公司");
            assertThat(d.companyShortName()).isEqualTo("超智");
        });
        assertThat(service.agentDefaultModel()).isNull();
    }

    @Test
    void rejectsAModelThatCannotInfer() {
        assertThatThrownBy(() -> service.update(new SystemSettingsDto("claude-listed-only", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.agentDefaultModel()).isNull();

        // A saved default that has since failed its check doesn't stop the other settings being saved.
        store.put(SettingsService.AGENT_DEFAULT_MODEL, "claude-gone");
        assertThat(service.update(new SystemSettingsDto("claude-gone", "OKG", null)).companyName()).isEqualTo("OKG");
    }

    @Test
    void thePublicAddressIsCheckedTidiedAndKeptUnlessChanged() {
        assertThat(service.publicUrl()).isNull();
        assertThat(service.update(new SystemSettingsDto(null, null, null, " https://ai.example.com/ ")).publicUrl())
                .isEqualTo("https://ai.example.com");
        assertThat(service.update(new SystemSettingsDto(null, "OKG", null)).publicUrl()).isEqualTo("https://ai.example.com");
        for (String bad : new String[]{"ai.example.com", "ftp://ai.example.com", "https://ai.example.com/?a=1", "https://"}) {
            assertThatThrownBy(() -> service.update(new SystemSettingsDto(null, null, null, bad)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.update(new SystemSettingsDto(null, null, null, "")).publicUrl()).isNull();
    }
}
