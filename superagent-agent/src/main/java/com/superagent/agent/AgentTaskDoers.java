package com.superagent.agent;

import com.superagent.agent.memory.AgentMemoryService;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.task.TaskDoers;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** An agent's tasks run as the agent: its character and its memory (the model is chosen per task). Not the SuperAgent's. */
@Component
class AgentTaskDoers implements TaskDoers {

    private final AgentStore agents;
    private final AgentMemoryService memory;

    AgentTaskDoers(AgentStore agents, AgentMemoryService memory) {
        this.agents = agents;
        this.memory = memory;
    }

    @Override
    public Optional<Doer> doer(String agentId) {
        return agentId == null ? Optional.empty() : agents.find(agentId).filter(a -> !a.isSuper()).map(this::doer);
    }

    private Doer doer(Agent a) {
        return new Doer(a.name(), AgentCharacters.systemPrompt(a), memory.read(a));
    }
}
