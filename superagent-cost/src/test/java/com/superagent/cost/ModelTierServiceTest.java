package com.superagent.cost;

import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.provider.ModelRouter;
import com.superagent.core.provider.ModelTier;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelTierServiceTest {

    private final Map<String, String> rows = new HashMap<>();
    private final List<ProviderModelDto> running = new ArrayList<>();
    private ModelTierService tiers;

    @BeforeEach
    void setUp() {
        ProviderService providers = mock(ProviderService.class);
        when(providers.storedModels(anyString())).thenReturn(List.of());
        when(providers.storedModels("claude")).thenReturn(running);
        when(providers.cliFor(any())).thenReturn(Optional.of("claude"));
        SettingsService settings = mock(SettingsService.class);
        when(settings.defaultTier()).thenReturn("standard");
        SystemSettingStore store = new SystemSettingStore() {
            @Override public Map<String, String> all() { return new HashMap<>(rows); }
            @Override public void put(String key, String value) { rows.put(key, value); }
        };
        tiers = new ModelTierService(providers, settings, store, "http://unused", 600);
    }

    private void runs(String... ids) {
        running.clear();
        for (String id : ids) {
            running.add(new ProviderModelDto(id, id, List.of(), null, "none", null, true).withInference("ok", null, null));
        }
    }

    private static ModelCatalog catalog(String json) throws Exception {
        return ModelCatalog.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String DIRECTORY = """
            {"openai":{"models":{
              "strong-new":{"cost":{"input":10,"output":50},"release_date":"2026-09-04"},
              "strong-old":{"cost":{"input":5,"output":30},"release_date":"2026-04-23"},
              "mid":{"cost":{"input":1,"output":5},"release_date":"2026-10-26"},
              "fast":{"cost":{"input":0.1,"output":0.5},"release_date":"2026-09-22"}}},
             "reseller":{"models":{"fast":{"cost":{"input":9,"output":9},"release_date":"2026-01-01"}}}}
            """;

    private String model(String tier, String keep) {
        return tiers.choose(tier, keep).map(ModelRouter.ModelChoice::model).orElse(null);
    }

    @Test
    void placesWhatCanRunByPriceTakingTheNewestAndKeepingARunningConversation() throws Exception {
        runs("strong-new", "strong-old", "mid", "fast");
        tiers.accept(catalog(DIRECTORY));
        assertThat(model("strong", null)).isEqualTo("strong-new"); // not the old one, though it's next in line
        assertThat(model(null, null)).isEqualTo("mid"); // the default tier
        assertThat(model("fast", null)).isEqualTo("fast"); // the vendor's price, not the reseller's
        assertThat(model("strong", "strong-old")).isEqualTo("strong-old"); // stays while it still belongs
        assertThat(model("strong", "mid")).isEqualTo("strong-new"); // the agent moved to another tier
    }

    @Test
    void aNewModelTakesPartAsSoonAsItCanRunEvenWithNoPriceAndTheAdminCanMoveIt() throws Exception {
        runs("mid", "fast");
        tiers.accept(catalog(DIRECTORY));
        assertThat(model("standard", null)).isEqualTo("fast"); // only two prices: nothing in the middle, so cheaper first

        runs("mid", "fast", "strong-new"); // starts passing its probe: no refresh needed
        assertThat(model("strong", null)).isEqualTo("strong-new");

        runs("mid", "fast", "never-heard-of-it");
        assertThat(tiers.overview().models()).filteredOn(m -> m.id().equals("never-heard-of-it")).singleElement()
                .satisfies(m -> { assertThat(m.tier()).isEqualTo("standard"); assertThat(m.input()).isNull(); });

        tiers.place("never-heard-of-it", "strong");
        assertThat(model("strong", null)).isEqualTo("never-heard-of-it");
        tiers.place("never-heard-of-it", "");
        assertThat(tiers.overview().models()).filteredOn(m -> m.id().equals("never-heard-of-it")).singleElement()
                .satisfies(m -> assertThat(m.manual()).isFalse());
    }

    @Test
    void anEmptyTierFallsToACheaperOneAndNothingRunnableIsEmpty() throws Exception {
        assertThat(tiers.choose("strong", null)).isEmpty();
        runs("fast");
        tiers.accept(catalog(DIRECTORY));
        assertThat(model("strong", null)).isEqualTo("fast"); // one price: standard, reached from any tier
    }

    @Test
    void pricesSurviveARestartWithoutNetwork() throws Exception {
        runs("mid", "fast");
        tiers.accept(catalog(DIRECTORY));
        assertThat(rows.get("cost.fact.mid")).startsWith("1.0|5.0|2026-10-26");
        setUp();
        runs("mid", "fast");
        assertThat(tiers.overview().models()).filteredOn(m -> m.id().equals("mid")).singleElement()
                .satisfies(m -> assertThat(m.input()).isEqualTo(1.0));
    }

    @Test
    void theCatalogRecognisesOtherSpellingsAndTheScreenshotSplitsSensibly() throws Exception {
        assertThat(ModelCatalog.key("openai/GPT-5.5")).isEqualTo("gpt-5-5");
        assertThat(ModelCatalog.key("claude-opus-5-5-20260922")).isEqualTo("claude-opus-5-5");
        assertThat(ModelCatalog.key("claude-opus-5-5[1m]")).isEqualTo("claude-opus-5-5");
        Map<String, ModelTier> split = ModelTiering.byPrice(Map.of("astra", 20.0, "gpt-5.5", 11.25, "sol", 4.0,
                "terra", 2.0, "v4-pro", 0.99, "flash", 0.26, "luna", 0.2));
        assertThat(split.get("astra")).isEqualTo(ModelTier.STRONG);
        assertThat(split.get("gpt-5.5")).isEqualTo(ModelTier.STRONG);
        assertThat(split.get("sol")).isEqualTo(ModelTier.STANDARD);
        assertThat(split.get("v4-pro")).isEqualTo(ModelTier.STANDARD);
        assertThat(split.get("flash")).isEqualTo(ModelTier.FAST);
        assertThat(ModelTiering.byPrice(Map.of("a", 1.0, "b", 1.2)).get("a")).isEqualTo(ModelTier.STANDARD);
    }
}
