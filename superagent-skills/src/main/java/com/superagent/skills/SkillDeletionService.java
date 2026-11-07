package com.superagent.skills;

import com.superagent.api.skill.SkillDeletionDto;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.skill.SkillDeletion;
import com.superagent.core.skill.SkillDeletionStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Deleting a skill from a conversation: the agent only proposes, the person who asked confirms on the card (every
 * agent using the skill loses it). Permission is checked when it's proposed and again when it's confirmed.
 */
@Service
public class SkillDeletionService {

    private final SkillService skills;
    private final SkillDeletionStore deletions;

    public SkillDeletionService(SkillService skills, SkillDeletionStore deletions) {
        this.skills = skills;
        this.deletions = deletions;
    }

    public SkillDeletion propose(String skillId, String user, String agentId, String sessionId, String messageId) {
        String name = skills.requireDeletable(skillId, user);
        Instant now = Instant.now();
        SkillDeletion d = new SkillDeletion(UUID.randomUUID().toString(), skillId, name, agentId, user, sessionId, messageId,
                SkillDeletion.Status.PENDING, now, now);
        deletions.save(d);
        return d;
    }

    /** The cards {@code user} proposed in this agent's conversation. */
    public List<SkillDeletionDto> list(String agentId, String user) {
        return deletions.forAgent(agentId).stream().filter(d -> Objects.equals(d.requester(), user)).map(this::toDto).toList();
    }

    public SkillDeletionDto confirm(String id, String user) {
        SkillDeletion d = pending(id, user);
        skills.delete(d.skillId(), user);
        SkillDeletion done = d.withStatus(SkillDeletion.Status.CONFIRMED);
        deletions.save(done);
        return toDto(done);
    }

    public SkillDeletionDto cancel(String id, String user) {
        SkillDeletion done = pending(id, user).withStatus(SkillDeletion.Status.CANCELLED);
        deletions.save(done);
        return toDto(done);
    }

    private SkillDeletion pending(String id, String user) {
        SkillDeletion d = deletions.find(id).filter(x -> Objects.equals(x.requester(), user))
                .orElseThrow(() -> new SkillNotFoundException(id));
        if (d.status() != SkillDeletion.Status.PENDING) {
            throw new ForbiddenException("This deletion was already " + d.status().name().toLowerCase(Locale.ROOT));
        }
        return d;
    }

    private SkillDeletionDto toDto(SkillDeletion d) {
        return new SkillDeletionDto(d.id(), d.skillId(), d.skillName(), d.messageId(),
                d.status().name().toLowerCase(Locale.ROOT), d.createdAt());
    }
}
