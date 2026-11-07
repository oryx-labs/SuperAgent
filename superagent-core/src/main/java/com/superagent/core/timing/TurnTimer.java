package com.superagent.core.timing;

import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Clocks one turn as it happens: started when the request comes in, told when each attempt at running the CLI
 * starts and ends, and fed the run's events through {@link #tap}. Only the last attempt is broken down; the ones
 * before it (a failed key, a retried session) count as {@code retry}. Safe to feed from the runner's threads.
 */
public final class TurnTimer {

    private final LongSupplier clock;
    private final long start;
    private long prepEnd = -1;
    private long retry;
    private int attempts;

    private long attemptStart = -1;
    private long firstEvent = -1;
    private long firstOutput = -1;
    private long runEnd = -1;
    private long toolsTotal;
    private final Deque<Object[]> open = new ArrayDeque<>(); // {tool name, started at}
    private final Map<String, long[]> tools = new LinkedHashMap<>(); // name -> {count, ms}

    public TurnTimer() {
        this(System::currentTimeMillis);
    }

    TurnTimer(LongSupplier clock) {
        this.clock = clock;
        this.start = clock.getAsLong();
    }

    /** A run of the CLI starts now; an earlier one, if any, failed and becomes retry time. */
    public synchronized void attemptStarted() {
        long now = clock.getAsLong();
        if (prepEnd < 0) {
            prepEnd = now;
        } else {
            retry += now - attemptStart;
        }
        attemptStart = now;
        firstEvent = firstOutput = runEnd = -1;
        toolsTotal = 0;
        open.clear();
        tools.clear();
        attempts++;
    }

    /** The run of the CLI is over (whether it worked or not). */
    public synchronized void attemptEnded() {
        long now = clock.getAsLong();
        runEnd = now;
        while (!open.isEmpty()) {
            closeTool(now); // a tool that never reported back ran until the end
        }
    }

    /** Passes every event on, noting when it came. */
    public AgentEventSink tap(AgentEventSink next) {
        return event -> {
            seen(event.type());
            next.emit(event);
        };
    }

    synchronized void seen(AgentEventType type) {
        long now = clock.getAsLong();
        if (firstEvent < 0) {
            firstEvent = now;
        }
        boolean output = type == AgentEventType.TEXT || type == AgentEventType.THINKING
                || type == AgentEventType.TOOL_USE || type == AgentEventType.RESULT;
        if (output && firstOutput < 0) {
            firstOutput = now;
        }
        if (type == AgentEventType.TOOL_RESULT && !open.isEmpty()) {
            closeTool(now);
        }
    }

    /** A tool call starts (its name is the event's payload). */
    public AgentEventSink tapTools(AgentEventSink next) {
        return event -> {
            if (event.type() == AgentEventType.TOOL_USE) {
                synchronized (this) {
                    seen(AgentEventType.TOOL_USE);
                    open.addLast(new Object[]{event.payload() == null || event.payload().isBlank() ? "tool" : event.payload().strip(),
                            clock.getAsLong()});
                }
            } else {
                seen(event.type());
            }
            next.emit(event);
        };
    }

    private void closeTool(long now) {
        Object[] t = open.pollFirst();
        long ms = Math.max(0, now - (long) t[1]);
        toolsTotal += ms;
        long[] agg = tools.computeIfAbsent((String) t[0], k -> new long[2]);
        agg[0]++;
        agg[1] += ms;
    }

    public synchronized TurnTiming finish(String sessionId, String messageId, String label) {
        long end = clock.getAsLong();
        if (attempts == 0) {
            return new TurnTiming(sessionId, messageId, label, Instant.ofEpochMilli(start), Instant.ofEpochMilli(end),
                    end - start, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
        long ran = runEnd < 0 ? end : runEnd;
        long startup = (firstEvent < 0 ? ran : firstEvent) - attemptStart;
        long model = firstEvent < 0 ? 0 : (firstOutput < 0 ? ran : firstOutput) - firstEvent;
        long output = firstOutput < 0 ? 0 : Math.max(0, ran - firstOutput - toolsTotal);
        List<TurnTiming.ToolTime> perTool = tools.entrySet().stream()
                .map(e -> new TurnTiming.ToolTime(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
                .sorted((a, b) -> Long.compare(b.ms(), a.ms())).toList();
        return new TurnTiming(sessionId, messageId, label, Instant.ofEpochMilli(start), Instant.ofEpochMilli(end),
                prepEnd - start, startup, model, Math.min(toolsTotal, Math.max(0, ran - (firstOutput < 0 ? ran : firstOutput))),
                output, retry, Math.max(0, end - ran), attempts, perTool);
    }
}
