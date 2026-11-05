package com.superagent.agent.memory;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMemoryState;
import com.superagent.core.agent.AgentMemoryStateStore;
import com.superagent.core.agent.AgentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** When an agent gives up on its session: ten real failures in a row; network trouble never counts. */
class SessionFailureTest {

    private static final class InMemoryStates implements AgentMemoryStateStore {
        final Map<String, AgentMemoryState> rows = new HashMap<>();
        @Override public Optional<AgentMemoryState> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public AgentMemoryState save(AgentMemoryState s) { rows.put(s.agentId(), s); return s; }
        @Override public void delete(String id) { rows.remove(id); }
    }

    private final InMemoryStates states = new InMemoryStates();
    // Only the failure bookkeeping is exercised, which needs nothing but the state store.
    private final AgentMemoryService memory = new AgentMemoryService(null, null, null, states, null, null, List.of(),
            10, 2, 30, 3, "Asia/Shanghai", 10, 10, null);
    private final Agent agent = new Agent("a1", "Ada", null, null, null, null, AgentStatus.ACTIVE, "alice",
            AgentKind.STANDARD, null, Instant.now(), Instant.now());

    @Test
    void networkTroubleIsNotTheSessionsFault() {
        assertThat(SessionHealth.isTransient("API Error: 503 Service Unavailable")).isTrue();
        assertThat(SessionHealth.isTransient("fetch failed: ECONNRESET")).isTrue();
        assertThat(SessionHealth.isTransient("Agent timed out after 600s")).isTrue();
        assertThat(SessionHealth.isTransient("API Error: 429 rate limit exceeded")).isTrue();
        assertThat(SessionHealth.isTransient("prompt is too long: 230000 tokens > 200000 maximum")).isFalse();
        assertThat(SessionHealth.isTransient("No conversation found with session ID")).isFalse();

        for (int i = 0; i < 20; i++) {
            assertThat(memory.recordFailure(agent, "network error: ECONNREFUSED")).isFalse();
        }
        assertThat(states.find("a1")).isEmpty(); // never even counted
    }

    @Test
    void theTenthFailureInARowGivesUpTheSessionAndASuccessStartsTheCountOver() {
        for (int i = 0; i < 9; i++) {
            assertThat(memory.recordFailure(agent, "prompt is too long")).isFalse();
        }
        memory.recordSuccess(agent);
        assertThat(states.find("a1").orElseThrow().sessionFailures()).isZero();

        for (int i = 0; i < 9; i++) {
            memory.recordFailure(agent, "prompt is too long");
        }
        assertThat(memory.recordFailure(agent, "prompt is too long")).isTrue();
        assertThat(states.find("a1").orElseThrow().sessionFailures()).isZero(); // the fresh session starts clean
    }
}
