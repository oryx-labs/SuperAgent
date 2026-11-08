package com.superagent.api.task;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the user's tasks can be filtered by, each with how many tasks and what they cost: topic tags, the agent that
 * ran them, where they came from. A complex task counts once, with its subtasks' cost.
 */
public record TaskFacetsDto(List<Facet> tags, List<Facet> agents, List<Facet> sources) {

    /** {@code key}: the tag, agent id or source; {@code label}: what to show (an agent's name). */
    public record Facet(String key, String label, int count, BigDecimal spend) {
    }
}
