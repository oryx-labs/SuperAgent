package com.superagent.core.usage;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;

/**
 * Keeps every model run's token usage, on the person it was for. Whoever runs a model passes its event sink through
 * {@link #tap}: the TOKENS events go here, never on to the run's output. Every attempt counts (a retry on another key
 * is billed too).
 */
public interface UsageRecorder {

    /** {@code tokensJson}: a TOKENS event's payload. Returns its estimated cost in USD, null when no price is known. */
    Double record(UsageContext context, String tokensJson);

    /** {@code sink} with the TOKENS events taken out and recorded ({@code recorder} null: just taken out). */
    static AgentEventSink tap(UsageRecorder recorder, UsageContext context, AgentEventSink sink) {
        return event -> {
            if (event.type() == AgentEventType.TOKENS) {
                if (recorder != null && context != null) {
                    recorder.record(context, event.payload());
                }
                return;
            }
            sink.emit(event);
        };
    }

    /** The event a runner emits at the end of a run. */
    static AgentEvent event(String sessionId, long seq, String tokensJson) {
        return new AgentEvent(sessionId, null, seq, AgentEventType.TOKENS, tokensJson, java.time.Instant.now());
    }
}
