package com.superagent.platform.provider;

import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Asks a model one question and returns its answer: no tools, no conversation, a scratch directory thrown away
 * after. For the system's own small decisions, run through the same CLIs (and key pool) as everything else.
 */
@Component
public class ModelCall {

    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private final ProviderService providers;
    private final CredentialService credentials;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "model-call-timeout");
        t.setDaemon(true);
        return t;
    });

    public ModelCall(List<AgentRunner> runners, ProviderService providers, CredentialService credentials) {
        runners.forEach(r -> this.runners.put(r.type(), r));
        this.providers = providers;
        this.credentials = credentials;
    }

    private com.superagent.core.usage.UsageRecorder usage; // null in tests: tokens aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsageRecorder(com.superagent.core.usage.UsageRecorder usage) {
        this.usage = usage;
    }

    /**
     * The answer, or an exception saying why there is none (no CLI for the model, an error, silence, a timeout).
     * {@code paying}: who the tokens go on (its cli is filled in here).
     */
    public String ask(String model, String prompt, long timeoutSeconds, com.superagent.core.usage.UsageContext paying) {
        AgentType type = providers.cliFor(model).map(AgentType::fromString).filter(runners::containsKey)
                .orElseThrow(() -> new IllegalStateException("No CLI can run " + model));
        Path scratch;
        try {
            scratch = Files.createTempDirectory("sa-ask-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder text = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        AgentEventSink sink = event -> {
            switch (event.type()) {
                case TEXT -> text.append(event.payload());
                case RESULT -> result.set(event.payload());
                case ERROR -> error.set(event.payload());
                default -> { /* no tools here */ }
            }
        };
        Cancellation cancellation = new Cancellation();
        var timeout = timer.schedule(cancellation::cancel, timeoutSeconds, TimeUnit.SECONDS);
        try {
            runners.get(type).run(new AgentRunRequest(UUID.randomUUID().toString(), null, scratch.toString(), prompt,
                    model, List.of(), false, null, null, List.of(), null,
                    credentials.current(type.cliName()).credential()),
                    com.superagent.core.usage.UsageRecorder.tap(usage, paying == null ? null : new com.superagent.core.usage.UsageContext(
                            paying.user(), paying.source(), paying.agentId(), paying.taskId(), paying.sessionId(), type.cliName()), sink),
                    cancellation);
        } finally {
            timeout.cancel(false);
            try (Stream<Path> walk = Files.walk(scratch)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // a leftover scratch dir is harmless
            }
        }
        if (cancellation.isCancelled()) {
            throw new IllegalStateException(model + " didn't answer within " + timeoutSeconds + "s");
        }
        String out = result.get() != null ? result.get() : text.toString();
        if (out.isBlank()) {
            throw new IllegalStateException(error.get() != null ? error.get() : model + " gave no answer");
        }
        return out;
    }
}
