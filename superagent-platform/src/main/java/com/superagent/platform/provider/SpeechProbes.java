package com.superagent.platform.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.media.SpeechEngine;
import com.superagent.core.media.SpeechModel;
import com.superagent.core.settings.SystemSettingStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Which speech models answer, per company: probed alongside the model check (an engine on a provider's key when that
 * provider is checked, a key-less one on every check), kept in the company's settings.
 */
@Component
public class SpeechProbes {

    static final String KEY = "speech.models";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /** One probe result. */
    public record Result(String engine, String model, boolean timed, boolean ok, String error, Instant checkedAt) {
    }

    private final SystemSettingStore store;
    private final ObjectProvider<SpeechEngine> engines;

    public SpeechProbes(SystemSettingStore store, ObjectProvider<SpeechEngine> engines) {
        this.store = store;
        this.engines = engines;
    }

    /** Probes the engines on {@code providerKey}'s key (and key-less ones) unless their results are fresh enough. */
    public void check(String providerKey, Duration freshEnough) {
        check(providerKey, freshEnough, freshEnough);
    }

    /** As {@link #check(String, Duration)}, an engine whose models answered staying fresh longer than a failing one. */
    public void check(String providerKey, Duration okFreshFor, Duration failingFreshFor) {
        List<Result> saved = results();
        Instant now = Instant.now();
        List<Result> out = new ArrayList<>(saved);
        boolean changed = false;
        for (SpeechEngine e : engines.orderedStream().toList()) {
            if (e.provider() != null && !e.provider().equals(providerKey)) {
                continue;
            }
            boolean fresh = saved.stream().anyMatch(r -> r.engine().equals(e.id())
                    && r.checkedAt() != null && r.checkedAt().plus(r.ok() ? okFreshFor : failingFreshFor).isAfter(now));
            if (fresh) {
                continue;
            }
            out.removeIf(r -> r.engine().equals(e.id()));
            for (SpeechEngine.Probe p : e.probe()) {
                out.add(new Result(e.id(), p.model(), p.timed(), p.ok(), p.error(), now));
            }
            changed = true;
        }
        if (changed) {
            save(out);
        }
    }

    /** Every speech model that answered its last probe, timed ones first. */
    public List<SpeechModel> available() {
        return results().stream().filter(Result::ok)
                .sorted(Comparator.comparing((Result r) -> !r.timed()).thenComparing(Result::model))
                .map(r -> new SpeechModel(r.engine(), r.model(), r.timed())).toList();
    }

    public List<Result> results() {
        String raw = store.all().get(KEY);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<List<Result>>() {
            }).stream().filter(Objects::nonNull).toList();
        } catch (IOException e) {
            return List.of(); // unreadable: probed again on the next check
        }
    }

    void save(List<Result> results) {
        try {
            store.put(KEY, JSON.writeValueAsString(results));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
