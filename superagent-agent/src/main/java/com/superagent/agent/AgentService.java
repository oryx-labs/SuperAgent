package com.superagent.agent;

import com.superagent.agent.chat.ChatService;
import com.superagent.agent.memory.AgentMemoryService;
import com.superagent.api.agent.AgentDto;
import com.superagent.api.agent.AgentMemoryDto;
import com.superagent.api.agent.AgentMemberDto;
import com.superagent.api.agent.CreateAgentRequest;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.ChatSegmentDto;
import com.superagent.api.chat.CreateSessionRequest;
import com.superagent.api.chat.SessionDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMember;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.platform.user.PeopleDirectory;
import com.superagent.skills.SkillService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
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
    private final AgentMemberStore members;
    private final ApplicationEventPublisher events;
    private final UserStore users;
    private final PeopleDirectory people;
    private final Object superLock = new Object();

    public AgentService(AgentStore store, ChatService chatService, SkillService skills, AgentMemoryService memory,
                        AgentMemberStore members, UserStore users,
                        ApplicationEventPublisher events, PeopleDirectory people) {
        this.events = events;
        this.members = members;
        this.users = users;
        this.people = people;
        this.store = store;
        this.chatService = chatService;
        this.skills = skills;
        this.memory = memory;
    }

    public AgentDto create(CreateAgentRequest request, String owner) {
        String name = request == null || request.name() == null || request.name().isBlank()
                ? "New agent" : request.name().trim();
        Agent agent = newAgent(owner, name, request == null ? null : request.avatar(),
                request == null ? null : request.character(), request == null ? null : request.persona(),
                request == null ? null : request.model(), AgentKind.STANDARD, null);
        if (request != null && request.skillIds() != null) {
            skills.setAgentSkills(agent.id(), request.skillIds(), owner);
        }
        return toDto(agent, chatService.currentSessionId(agent.id()), owner);
    }

    /** A team member the user's SuperAgent hires; it appears in the user's list like any other agent. */
    public Agent createTeamMember(String owner, String superAgentId, String name, String avatar, String persona,
                                  String model, List<String> skillIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A team member needs a name");
        }
        Agent agent = newAgent(owner, name.trim(), avatar, null, persona, model, AgentKind.STANDARD, superAgentId);
        if (skillIds != null) {
            skills.setAgentSkills(agent.id(), skillIds, owner);
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

    /** The user's agents (their SuperAgent first, created now if they have none yet), then agents shared with them. */
    public List<AgentDto> list(String owner) {
        ensureSuperAgent(owner);
        List<AgentDto> out = new ArrayList<>(owned(owner).stream()
                .sorted(Comparator.comparing((Agent a) -> !a.isSuper()))
                .map(a -> toDto(a, chatService.currentSessionId(a.id()), owner))
                .toList());
        sharedWith(owner).forEach(a -> out.add(toDto(a, chatService.currentSessionId(a.id()), owner)));
        return out;
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

    public AgentDto get(String id, String user) {
        Agent agent = requireViewer(id, user);
        return toDto(agent, chatService.currentSessionId(id), user);
    }

    /** The agent if it is the user's and live; throws {@link AgentNotFoundException} otherwise. */
    /** The agent's name, for labels (null when it's gone). */
    public String findName(String id) {
        return store.find(id).map(Agent::name).orElse(null);
    }

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
            skills.setAgentSkills(id, request.skillIds(), agent.owner());
        }
        return toDto(updated, chatService.currentSessionId(id), owner);
    }

    public void delete(String id, String owner) {
        Agent agent = require(id, owner);
        if (agent.isSuper()) {
            throw new IllegalArgumentException("SuperAgent can't be deleted");
        }
        store.update(agent.deleted());
        memory.forget(agent); // the agent's memory goes with it; its chat history is kept like the agent row
    }

    /** Its sessions, newest first; callers check access first. */
    public List<com.superagent.api.chat.AgentSessionDto> sessions(String id) {
        return chatService.agentSessions(id);
    }

    /** The agent's whole conversation: every session it has had, oldest first. */
    public List<ChatSegmentDto> history(String id, String user) {
        return chatService.agentHistory(requireViewer(id, user).id());
    }

    /** Where each reply's time went, for everyone who can read the conversation. */
    public List<com.superagent.api.timing.TurnTimingDto> timings(String id, String user) {
        List<String> sessions = chatService.agentHistory(requireViewer(id, user).id()).stream().map(ChatSegmentDto::sessionId).toList();
        return chatService.timings(sessions).stream().map(com.superagent.api.timing.TurnTimingDto::of).toList();
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

    private AgentDto toDto(Agent a, String sessionId, String viewer) {
        List<AgentMemberDto> people = new ArrayList<>();
        people.add(new AgentMemberDto(a.owner(), displayName(a.owner()), "owner"));
        members.members(a.id()).forEach(m -> people.add(new AgentMemberDto(m.username(), displayName(m.username()),
                "collaborator")));
        String parent = parentOf(a.id());
        return new AgentDto(a.id(), a.name(), a.avatar(), a.character(), a.persona(), a.model(), skills.agentSkillIds(a.id()),
                a.status().name().toLowerCase(), a.kind().name().toLowerCase(), a.managedBy(), sessionId,
                a.createdAt(), a.updatedAt(), a.owner(), Objects.equals(a.owner(), viewer) ? "owner" : "collaborator",
                people, shareable(a), a.tier(), parent, parent == null ? null : findName(parent));
    }

    // --- memory ---

    /** Members may read the agent's memory; only its owner changes it. */
    public AgentMemoryDto memory(String id, String user) {
        Agent agent = requireViewer(id, user);
        return new AgentMemoryDto(memory.read(agent), memory.updatedAt(agent), Objects.equals(agent.owner(), user),
                memory.distilling(agent.id()));
    }

    public AgentMemoryDto editMemory(String id, String user, String text) {
        Agent agent = require(id, user);
        memory.edit(agent, text, user);
        return memory(id, user);
    }

    /** Folds the conversation not in memory yet into it now, in the background. */
    public AgentMemoryDto distillMemory(String id, String user) {
        Agent agent = require(id, user);
        memory.distillNow(agent);
        return memory(id, user);
    }

    private com.superagent.core.project.ProjectAccess projects; // null in tests: no projects
    /** The model its conversation runs on (the default, or the strongest that can run); null when none can. */
    public String model(String id, String user) {
        return chatService.resolveModel(requireViewer(id, user));
    }
    private com.superagent.core.agent.AgentParentStore parents; // null in tests: no parent agents

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setParents(com.superagent.core.agent.AgentParentStore parents) {
        this.parents = parents;
    }

    // --- parent agents ---

    /**
     * Puts the owner's agent under another of theirs ({@code parentId} null or blank: under no one). The SuperAgent
     * leads everyone, so it neither has a parent nor is one; an agent can't end up under itself.
     */
    public void setParent(String agentId, String owner, String parentId) {
        Agent agent = require(agentId, owner);
        if (agent.isSuper()) {
            throw new IllegalArgumentException("SuperAgent 不能有上级");
        }
        if (parents == null) {
            throw new IllegalStateException("parent agents need a parent store");
        }
        String parent = trimToNull(parentId);
        if (parent != null) {
            Agent above = require(parent, owner);
            if (above.isSuper()) {
                throw new IllegalArgumentException("SuperAgent 本来就带领所有 Agent，不用设为上级；不设上级即可");
            }
            for (String at = parent; at != null; at = parentOf(at)) {
                if (at.equals(agentId)) {
                    throw new IllegalArgumentException("「" + above.name() + "」是「" + agent.name() + "」的下级，不能反过来做它的上级");
                }
            }
        }
        parents.set(agentId, parent);
    }

    /** The agent's parent, while that agent still exists. */
    public String parentOf(String agentId) {
        if (parents == null) {
            return null;
        }
        return parents.parentOf(agentId).filter(p -> store.find(p).filter(a -> a.status() != AgentStatus.DELETED).isPresent())
                .orElse(null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProjects(com.superagent.core.project.ProjectAccess projects) {
        this.projects = projects;
    }

    // --- collaboration ---

    /** Owner or active collaborator: may see the agent's conversation and direct it. */
    public Agent requireViewer(String id, String user) {
        Agent agent = store.find(id).filter(a -> a.status() != AgentStatus.DELETED)
                .orElseThrow(() -> new AgentNotFoundException(id));
        if (Objects.equals(agent.owner(), user) || isMember(id, user)
                || (projects != null && projects.sharesProject(id, user))) {
            return agent;
        }
        throw new AgentNotFoundException(id); // someone else's private agent reads as "not found"
    }

    public boolean isMember(String agentId, String user) {
        return user != null && members.members(agentId).stream().anyMatch(m -> m.username().equals(user));
    }

    /** Agents others shared with the user, one by one or through a project they are in; live ones only. */
    private List<Agent> sharedWith(String user) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(members.agentsOf(user));
        if (projects != null) {
            ids.addAll(projects.agentsThroughProjects(user));
        }
        return ids.stream().map(store::find).flatMap(Optional::stream)
                .filter(a -> a.status() != AgentStatus.DELETED && !Objects.equals(a.owner(), user))
                .toList();
    }

    /**
     * Every agent but the SuperAgent can be shared — the ones it hired included. The SuperAgent is the owner's own
     * leader: it acts on their requests and the messages their data sources receive.
     */
    private boolean shareable(Agent a) {
        return !a.isSuper();
    }

    /** Adds collaborators (owner only). They see the whole history, including what was said before they joined. */
    public AgentDto invite(String id, String owner, List<String> usernames) {
        Agent agent = require(id, owner);
        if (!shareable(agent)) {
            throw new IllegalArgumentException("This agent works on your private data and can't be shared");
        }
        Instant now = Instant.now();
        for (String raw : usernames == null ? List.<String>of() : usernames) {
            String name = raw == null ? "" : raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            User user = people.resolve(name).filter(User::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("No such user: " + name));
            if (user.id().equals(owner) || isMember(id, user.id())) {
                continue;
            }
            members.save(new AgentMember(id, user.id(), owner, true, now, now));
            events.publishEvent(new NoticeEvents.AgentShared(id, agent.name(), user.id(), displayName(owner)));
            log.info("agent {} shared by {} with {}", id, owner, user.id());
        }
        return toDto(agent, chatService.currentSessionId(id), owner);
    }

    /** User ids of the agent's active collaborators (the owner isn't one). */
    public List<String> memberUsernames(String id) {
        return members.members(id).stream().map(AgentMember::username).toList();
    }

    /** Whether the owner may share this agent at all (only the SuperAgent can't be). */
    public boolean canShare(String id, String owner) {
        return shareable(require(id, owner));
    }

    /** The owner removes someone, or a collaborator leaves (themselves). {@code who}: a user id or username. */
    public void removeMember(String id, String caller, String who) {
        String username = people.resolve(who).map(User::id).orElse(who);
        Agent agent = requireViewer(id, caller);
        boolean self = Objects.equals(caller, username);
        if (!self && !Objects.equals(agent.owner(), caller)) {
            throw new AgentForbiddenException("Only the owner can remove collaborators");
        }
        members.members(id).stream().filter(m -> m.username().equals(username)).findFirst()
                .ifPresent(m -> members.save(new AgentMember(id, username, m.addedBy(), false, m.createdAt(), Instant.now())));
    }

    /** A person's name to show, from their user id. */
    public String displayName(String userId) {
        if (userId == null) {
            return null;
        }
        return people.find(userId).map(u -> u.displayName() == null || u.displayName().isBlank()
                ? u.username() : u.displayName()).orElse(userId);
    }
}
