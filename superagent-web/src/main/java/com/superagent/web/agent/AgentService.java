package com.superagent.web.agent;

import com.superagent.api.agent.AgentDto;
import com.superagent.api.agent.CreateAgentRequest;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.ChatSegmentDto;
import com.superagent.api.chat.CreateSessionRequest;
import com.superagent.api.chat.SessionDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.web.chat.ChatService;
import com.superagent.web.memory.AgentMemoryService;
import com.superagent.web.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages Agents (personas). Every agent belongs to one user and is visible only to them; an
 * agent that isn't the caller's reads as "not found", so ids can't be probed. Each user also
 * has exactly one built-in {@link AgentKind#SUPER SuperAgent}, created on first use, which can't
 * be deleted and whose leader character is fixed by the platform.
 *
 * <p>Phase 1 is strictly one agent to one chat session: creating an agent immediately provisions
 * its owning session (via {@link ChatService}) so "open the agent → chat" works right away.
 * Deletion is a soft delete; the owning session (and its history) is kept.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Built-in emoji person avatars; a new agent gets a random one unless the user picks. */
    public static final List<String> AVATARS = List.of(
            "🧑‍💻", "👩‍💻", "👨‍💻", "🧑‍🔬", "👩‍🔬", "👨‍🔬", "🧑‍🎨", "👩‍🎨",
            "🧑‍🚀", "👩‍🚀", "🧑‍🏫", "👩‍🏫", "🧑‍💼", "👩‍💼", "👨‍💼", "🧑‍🔧",
            "👩‍🔧", "🧑‍⚕️", "🧑‍🍳", "🧑‍🎓", "🕵️", "🧙", "🦸", "🥷");

    public static final String SUPER_AGENT_NAME = "SuperAgent";
    private static final String SUPER_AGENT_AVATAR = "🦸";

    private final AgentStore store;
    private final ChatService chatService;
    private final SkillService skills;
    private final AgentMemoryService memory;
    private final String adminUsername;
    private final Object superLock = new Object();

    public AgentService(AgentStore store, ChatService chatService, SkillService skills, AgentMemoryService memory,
                        @Value("${superagent.auth.admin.username:admin}") String adminUsername) {
        this.store = store;
        this.chatService = chatService;
        this.skills = skills;
        this.memory = memory;
        this.adminUsername = adminUsername;
    }

    /**
     * Agents created before agents had owners belong to nobody, so no one would see them any more.
     * They are handed to the initial admin rather than shown to everyone.
     */
    @EventListener(ApplicationReadyEvent.class)
    void claimUnownedAgents() {
        List<Agent> orphans = store.list().stream().filter(a -> a.owner() == null).toList();
        for (Agent a : orphans) {
            store.update(a.withOwner(adminUsername));
        }
        if (!orphans.isEmpty()) {
            log.info("assigned {} agent(s) created before agents had owners to {}", orphans.size(), adminUsername);
        }
    }

    public AgentDto create(CreateAgentRequest request, String owner) {
        String name = request == null || request.name() == null || request.name().isBlank()
                ? "New agent" : request.name().trim();
        Agent agent = newAgent(owner, name, request == null ? null : request.avatar(),
                request == null ? null : request.character(), request == null ? null : request.persona(),
                request == null ? null : request.model(), AgentKind.STANDARD, null);
        if (request != null && request.skillIds() != null) {
            skills.setAgentSkills(agent.id(), request.skillIds());
        }
        return toDto(agent, chatService.currentSessionId(agent.id()));
    }

    /** A team member the user's SuperAgent hires; it appears in the user's list like any other agent. */
    public Agent createTeamMember(String owner, String superAgentId, String name, String avatar, String persona,
                                  String model, List<String> skillIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A team member needs a name");
        }
        Agent agent = newAgent(owner, name.trim(), avatar, null, persona, model, AgentKind.STANDARD, superAgentId);
        if (skillIds != null) {
            skills.setAgentSkills(agent.id(), skillIds);
        }
        return agent;
    }

    private Agent newAgent(String owner, String name, String avatar, String character, String persona, String model,
                           AgentKind kind, String managedBy) {
        String requested = trimToNull(avatar);
        Instant now = Instant.now();
        Agent agent = new Agent(UUID.randomUUID().toString(), name,
                requested != null ? validAvatar(requested) : AVATARS.get(ThreadLocalRandom.current().nextInt(AVATARS.size())),
                validCharacter(trimToNull(character)), trimToNull(persona), trimToNull(model), AgentStatus.ACTIVE,
                owner, kind, managedBy, now, now);
        store.create(agent);
        // Provision the owning session so the agent is chat-ready on first open.
        SessionDto session = chatService.createSession(new CreateSessionRequest(null, name, agent.model(), agent.id()));
        log.info("created {} agent {} ({}) for {} with session {}", kind, agent.id(), name, owner, session.id());
        return agent;
    }

    /** The user's agents, their SuperAgent first (created now if they have none yet). */
    public List<AgentDto> list(String owner) {
        ensureSuperAgent(owner);
        return owned(owner).stream()
                .sorted(Comparator.comparing((Agent a) -> !a.isSuper()))
                .map(a -> toDto(a, chatService.currentSessionId(a.id())))
                .toList();
    }

    /** The user's live agents, in the store's order (most recently updated first). */
    public List<Agent> owned(String owner) {
        return store.list().stream()
                .filter(a -> a.status() != AgentStatus.DELETED && Objects.equals(a.owner(), owner))
                .toList();
    }

    /** The user's SuperAgent, created (with its session) the first time it's needed. */
    public Agent ensureSuperAgent(String owner) {
        synchronized (superLock) {
            return findSuperAgent(owner).orElseGet(() ->
                    newAgent(owner, SUPER_AGENT_NAME, SUPER_AGENT_AVATAR, null, null, null, AgentKind.SUPER, null));
        }
    }

    public Optional<Agent> findSuperAgent(String owner) {
        return owned(owner).stream().filter(Agent::isSuper).findFirst();
    }

    public AgentDto get(String id, String owner) {
        Agent agent = require(id, owner);
        return toDto(agent, chatService.currentSessionId(id));
    }

    /** The agent if it is the user's and live; throws {@link AgentNotFoundException} otherwise. */
    public Agent require(String id, String owner) {
        return store.find(id)
                .filter(a -> a.status() != AgentStatus.DELETED && Objects.equals(a.owner(), owner))
                .orElseThrow(() -> new AgentNotFoundException(id));
    }

    public AgentDto update(String id, UpdateAgentRequest request, String owner) {
        Agent agent = require(id, owner);
        String avatar = trimToNull(request.avatar()) == null ? agent.avatar() : validAvatar(request.avatar().trim());
        Agent updated;
        if (agent.isSuper()) {
            // The leader's name and character are the platform's; only its look, model and skills are the user's.
            updated = new Agent(agent.id(), agent.name(), avatar, null, null, trimToNull(request.model()),
                    agent.status(), agent.owner(), agent.kind(), agent.managedBy(), agent.createdAt(), Instant.now());
        } else {
            String name = request.name() == null || request.name().isBlank() ? agent.name() : request.name().trim();
            // character is always sent by the edit form: null/blank switches the agent to a custom character.
            updated = new Agent(agent.id(), name, avatar, validCharacter(trimToNull(request.character())),
                    trimToNull(request.persona()), trimToNull(request.model()), agent.status(), agent.owner(),
                    agent.kind(), agent.managedBy(), agent.createdAt(), Instant.now());
        }
        store.update(updated);
        if (request.skillIds() != null) {
            skills.setAgentSkills(id, request.skillIds());
        }
        return toDto(updated, chatService.currentSessionId(id));
    }

    public void delete(String id, String owner) {
        Agent agent = require(id, owner);
        if (agent.isSuper()) {
            throw new IllegalArgumentException("SuperAgent can't be deleted");
        }
        store.update(agent.deleted());
        memory.forget(agent); // the agent's memory goes with it; its chat history is kept like the agent row
    }

    /** The agent's whole conversation: every session it has had, oldest first. */
    public List<ChatSegmentDto> history(String id, String owner) {
        return chatService.agentHistory(require(id, owner).id());
    }

    /** Only built-ins are accepted, so an avatar is always a known, short emoji. */
    private static String validAvatar(String avatar) {
        if (!AVATARS.contains(avatar)) {
            throw new IllegalArgumentException("Unknown avatar; choose one of the built-in avatars");
        }
        return avatar;
    }

    private static String validCharacter(String key) {
        if (key != null && AgentCharacters.find(key).isEmpty()) {
            throw new IllegalArgumentException("Unknown character: " + key);
        }
        return key;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private AgentDto toDto(Agent a, String sessionId) {
        return new AgentDto(a.id(), a.name(), a.avatar(), a.character(), a.persona(), a.model(), skills.agentSkillIds(a.id()),
                a.status().name().toLowerCase(), a.kind().name().toLowerCase(), a.managedBy(), sessionId,
                a.createdAt(), a.updatedAt());
    }
}
