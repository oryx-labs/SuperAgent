package com.superagent.agent;

import com.superagent.api.agent.AgentInviteDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentInvite;
import com.superagent.core.agent.AgentInviteStore;
import com.superagent.core.user.User;
import com.superagent.platform.user.PeopleDirectory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Adding people to an agent from inside its conversation. The agent can only <em>propose</em> an invitation;
 * joining shows the newcomer the whole conversation, so it takes effect only when the agent's owner confirms it
 * on the card in the chat. Removing someone needs no confirmation. Authority is always the person whose message
 * the agent is answering: only the owner invites or removes others; a collaborator may only leave.
 */
@Service
public class AgentInviteService {

    private static final int MAX_MATCHES = 10;

    private final AgentService agents;
    private final AgentInviteStore invites;
    private final PeopleDirectory people;

    public AgentInviteService(AgentService agents, AgentInviteStore invites, PeopleDirectory people) {
        this.agents = agents;
        this.invites = invites;
        this.people = people;
    }

    /** People whose name, username or phone contains {@code query}, for the agent to pick from (never the caller). */
    public List<User> findPeople(String caller, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            throw new IllegalArgumentException("Say who to look for");
        }
        return people.enabled().stream().filter(u -> !u.id().equals(caller))
                .filter(u -> Stream.of(u.username(), u.displayName(), u.phone())
                        .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(q)))
                .limit(MAX_MATCHES).toList();
    }

    /** Proposes adding {@code usernames}; nothing changes until the owner confirms. */
    public AgentInvite propose(String agentId, String caller, List<String> usernames, String sessionId, String messageId) {
        Agent agent = agents.requireViewer(agentId, caller);
        if (!Objects.equals(agent.owner(), caller)) {
            throw new AgentForbiddenException("Only the agent's owner can invite people");
        }
        if (!agents.canShare(agentId, caller)) {
            throw new IllegalArgumentException("This agent works on the owner's private data and can't be shared");
        }
        List<String> already = agents.memberUsernames(agentId);
        List<String> wanted = new ArrayList<>();
        // Named by username (said to the agent) or id; kept as ids.
        for (String raw : new LinkedHashSet<>(usernames == null ? List.<String>of() : usernames)) {
            String name = raw == null ? "" : raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            User u = people.resolve(name).filter(User::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("No such user: " + name + " (look them up first)"));
            if (!u.id().equals(caller) && !already.contains(u.id()) && !wanted.contains(u.id())) {
                wanted.add(u.id());
            }
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Everyone named is already in this agent");
        }
        Instant now = Instant.now();
        AgentInvite invite = new AgentInvite(UUID.randomUUID().toString(), agentId, caller, wanted, sessionId, messageId,
                AgentInvite.Status.PENDING, now, now);
        invites.save(invite);
        return invite;
    }

    /** The agent's invitations, for its members to see on the cards. */
    public List<AgentInviteDto> list(String agentId, String user) {
        agents.requireViewer(agentId, user);
        return invites.forAgent(agentId).stream().map(this::toDto).toList();
    }

    /** The owner confirms: the people join (and are notified as usual). */
    public AgentInviteDto confirm(String agentId, String inviteId, String user) {
        AgentInvite invite = pending(agentId, inviteId, user);
        agents.invite(agentId, user, invite.usernames());
        AgentInvite done = invite.withStatus(AgentInvite.Status.CONFIRMED);
        invites.save(done);
        return toDto(done);
    }

    public AgentInviteDto cancel(String agentId, String inviteId, String user) {
        AgentInvite done = pending(agentId, inviteId, user).withStatus(AgentInvite.Status.CANCELLED);
        invites.save(done);
        return toDto(done);
    }

    /**
     * Invitations {@code user} proposed for their own agents from the given conversations (the SuperAgent's chat
     * sessions), so its chat can show their cards.
     */
    public List<AgentInviteDto> proposedIn(String user, java.util.Set<String> sessionIds) {
        return agents.owned(user).stream().flatMap(a -> invites.forAgent(a.id()).stream())
                .filter(i -> i.sessionId() != null && sessionIds.contains(i.sessionId()))
                .map(this::toDto).toList();
    }

    private AgentInviteDto toDto(AgentInvite i) {
        String agentName = agents.findName(i.agentId());
        return new AgentInviteDto(i.id(), i.usernames(), i.usernames().stream().map(agents::displayName).toList(),
                i.messageId(), i.status().name().toLowerCase(Locale.ROOT), i.createdAt(), i.agentId(), agentName);
    }

    private AgentInvite pending(String agentId, String inviteId, String user) {
        Agent agent = agents.requireViewer(agentId, user);
        if (!Objects.equals(agent.owner(), user)) {
            throw new AgentForbiddenException("Only the agent's owner can confirm an invitation");
        }
        AgentInvite invite = invites.find(inviteId).filter(i -> i.agentId().equals(agentId))
                .orElseThrow(() -> new IllegalArgumentException("No such invitation: " + inviteId));
        if (invite.status() != AgentInvite.Status.PENDING) {
            throw new IllegalArgumentException("This invitation was already " + invite.status().name().toLowerCase(Locale.ROOT));
        }
        return invite;
    }
}
