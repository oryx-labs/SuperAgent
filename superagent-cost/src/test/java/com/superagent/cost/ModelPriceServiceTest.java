package com.superagent.cost;

import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.platform.provider.ProviderService;
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

/** What each model that can run costs: the directory's price, or the admin's; and whether tasks may use it. */
class ModelPriceServiceTest {

    private final Map<String, String> rows = new HashMap<>();
    private final List<ProviderModelDto> running = new ArrayList<>();
    private final ModelPriceService prices;

    ModelPriceServiceTest() {
        ProviderService providers = mock(ProviderService.class);
        when(providers.storedModels(anyString())).thenReturn(List.of());
        when(providers.storedModels("claude")).thenReturn(running);
        when(providers.cliFor(any())).thenReturn(Optional.of("claude"));
        SystemSettingStore store = new SystemSettingStore() {
            @Override public Map<String, String> all() { return new HashMap<>(rows); }
            @Override public void put(String key, String value) { if (value == null) rows.remove(key); else rows.put(key, value); }
        };
        prices = new ModelPriceService(providers, store, "http://unused", 600);
    }

    @Test
    void listsWhatCanRunCheapestFirstWithTheAdminsPricesAndTaskSwitch() throws Exception {
        for (String id : List.of("strong", "mid", "fast", "unpriced")) {
            running.add(new ProviderModelDto(id, id, List.of(), null, "none", null, true).withInference("ok", null, null));
        }
        prices.useCatalog(ModelCatalog.parse(new ByteArrayInputStream("""
                {"openai":{"models":{
                  "strong":{"cost":{"input":10,"output":50,"cache_read":1},"release_date":"2026-09-04"},
                  "mid":{"cost":{"input":1,"output":5},"release_date":"2026-10-26"},
                  "fast":{"cost":{"input":0.1,"output":0.5},"release_date":"2026-09-22"}}}}
                """.getBytes(StandardCharsets.UTF_8))));

        assertThat(prices.candidates()).extracting(ModelPriceService.Candidate::id).containsExactly("fast", "mid", "strong", "unpriced");
        assertThat(prices.cheapest()).map(c -> c.model()).contains("fast");
        assertThat(prices.price("strong")).map(ModelPriceService.Price::cacheRead).contains(1.0);

        // A relay bills its own price: the admin's wins, and clearing it goes back to the list.
        prices.setPrice("strong", 2.0, 8.0, null, null);
        assertThat(prices.price("strong")).map(ModelPriceService.Price::input).contains(2.0);
        assertThat(prices.overview().models()).filteredOn(m -> m.id().equals("strong")).singleElement()
                .satisfies(m -> assertThat(m.manual()).isTrue());
        prices.setPrice("strong", null, null, null, null);
        assertThat(prices.price("strong")).map(ModelPriceService.Price::input).contains(10.0);

        // Kept out of tasks: still listed, marked off.
        prices.setForTasks("mid", false);
        assertThat(prices.candidates()).filteredOn(c -> !c.forTasks()).extracting(ModelPriceService.Candidate::id).containsExactly("mid");
    }

    @Test
    void conversationsLeaveABrokenDefaultForTheBackupThenTheStrongestAndComeBack() throws Exception {
        for (String id : List.of("strong", "mid", "fast")) {
            running.add(new ProviderModelDto(id, id, List.of(), null, "none", null, true).withInference("ok", null, null));
        }
        prices.useCatalog(ModelCatalog.parse(new ByteArrayInputStream("""
                {"openai":{"models":{"strong":{"cost":{"input":10,"output":50}},"mid":{"cost":{"input":1,"output":5}},
                  "fast":{"cost":{"input":0.1,"output":0.5}}}}}
                """.getBytes(StandardCharsets.UTF_8))));
        com.superagent.platform.settings.SettingsService settings = mock(com.superagent.platform.settings.SettingsService.class);
        when(settings.agentDefaultModel()).thenReturn("mid");
        when(settings.backupModels()).thenReturn(List.of("fast"));
        prices.setSettings(settings);
        List<Object> told = new ArrayList<>();
        prices.setEvents(told::add);

        assertThat(prices.chatModels()).containsExactly("mid", "fast", "strong");
        assertThat(told).isEmpty();

        prices.reportFailure("mid", "API Error: 503");
        assertThat(prices.chatModels()).containsExactly("fast", "strong"); // the backup first, then the strongest
        prices.chatModels();
        assertThat(told).singleElement().satisfies(e -> {
            var d = (com.superagent.core.notice.NoticeEvents.DefaultModelChanged) e;
            assertThat(d.down()).isTrue();
            assertThat(d.inUse()).isEqualTo("fast");
            assertThat(d.reason()).contains("503");
        }); // told once, not on every turn

        // Its time out is up: the default is tried again, and the admins hear it's back.
        prices.forgetFailures();
        assertThat(prices.chatModels().get(0)).isEqualTo("mid");
        assertThat(told).hasSize(2);
        assertThat(((com.superagent.core.notice.NoticeEvents.DefaultModelChanged) told.get(1)).down()).isFalse();
    }
}
