package com.superagent.web.agent;

import com.superagent.api.agent.AgentDto;
import com.superagent.api.agent.CreateAgentRequest;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.CreateSessionRequest;
import com.superagent.api.chat.SessionDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.web.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages Agents (personas). Phase 1 is strictly one agent to one chat session:
 * creating an agent immediately provisions its owning session (via
 * {@link ChatService}) so "open the agent → chat" works right away. Deletion is a
 * soft delete; the owning session (and its history) is kept.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Built-in emoji person avatars; a new agent gets a random one unless the user picks. */
    public static final List<String> AVATARS = List.of(
            "🧑‍💻", "👩‍💻", "👨‍💻", "🧑‍🔬", "👩‍🔬", "👨‍🔬", "🧑‍🎨", "👩‍🎨",
            "🧑‍🚀", "👩‍🚀", "🧑‍🏫", "👩‍🏫", "🧑‍💼", "👩‍💼", "👨‍💼", "🧑‍🔧",
            "👩‍🔧", "🧑‍⚕️", "🧑‍🍳", "🧑‍🎓", "🕵️", "🧙", "🦸", "🥷");

    private final AgentStore store;
    private final ChatService chatService;

    public AgentService(AgentStore store, ChatService chatService) {
        this.store = store;
        this.chatService = chatService;
    }

    public AgentDto create(CreateAgentRequest request) {
        String name = request == null || request.name() == null || request.name().isBlank()
                ? "New agent" : request.name().trim();
        String persona = request == null ? null : trimToNull(request.persona());
        String model = request == null ? null : trimToNull(request.model());
        String requested = request == null ? null : trimToNull(request.avatar());
        String avatar = requested != null ? validAvatar(requested)
                : AVATARS.get(ThreadLocalRandom.current().nextInt(AVATARS.size()));

        Instant now = Instant.now();
        Agent agent = new Agent(UUID.randomUUID().toString(), name, avatar, persona, model,
                AgentStatus.ACTIVE, now, now);
        store.create(agent);

        // Provision the owning session so the agent is chat-ready on first open.
        SessionDto session = chatService.createSession(
                new CreateSessionRequest(null, name, model, agent.id()));
        log.info("created agent {} ({}) with session {}", agent.id(), name, session.id());
        return toDto(agent, session.id());
    }

    public List<AgentDto> list() {
        return store.list().stream()
                .filter(a -> a.status() != AgentStatus.DELETED)
                .map(a -> toDto(a, chatService.primarySessionId(a.id())))
                .toList();
    }

    public AgentDto get(String id) {
        Agent agent = require(id);
        return toDto(agent, chatService.primarySessionId(id));
    }

    public AgentDto update(String id, UpdateAgentRequest request) {
        Agent agent = require(id);
        String name = request.name() == null || request.name().isBlank() ? agent.name() : request.name().trim();
        String avatar = trimToNull(request.avatar()) == null ? agent.avatar() : validAvatar(request.avatar().trim());
        Agent updated = new Agent(agent.id(), name, avatar, trimToNull(request.persona()),
                trimToNull(request.model()), agent.status(), agent.createdAt(), Instant.now());
        store.update(updated);
        return toDto(updated, chatService.primarySessionId(id));
    }

    public void delete(String id) {
        store.update(require(id).deleted());
    }

    private Agent require(String id) {
        return store.find(id)
                .filter(a -> a.status() != AgentStatus.DELETED)
                .orElseThrow(() -> new AgentNotFoundException(id));
    }

    /** Only built-ins are accepted, so an avatar is always a known, short emoji. */
    private static String validAvatar(String avatar) {
        if (!AVATARS.contains(avatar)) {
            throw new IllegalArgumentException("Unknown avatar; choose one of the built-in avatars");
        }
        return avatar;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AgentDto toDto(Agent a, String sessionId) {
        return new AgentDto(a.id(), a.name(), a.avatar(), a.persona(), a.model(),
                a.status().name().toLowerCase(), sessionId, a.createdAt(), a.updatedAt());
    }
}
