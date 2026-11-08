package com.superagent.event;

import com.superagent.agent.AgentNotFoundException;
import com.superagent.agent.AgentService;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.tenant.CompanyStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The inbox used to post into a "秘书" agent of its own; SuperAgent handles received messages now, and hires
 * a secretary into its team itself if it wants one. Removes the old secretary agents once.
 */
@Component
class SecretaryCleanup {

    private static final Logger log = LoggerFactory.getLogger(SecretaryCleanup.class);

    private final EventSourceStore sources;
    private final AgentService agents;

    SecretaryCleanup(EventSourceStore sources, AgentService agents) {
        this.sources = sources;
        this.agents = agents;
    }

    @EventListener(CompanyStarted.class)
    void removeSecretaries() {
        for (EventSource s : sources.list()) {
            if (s.secretaryAgentId() == null) {
                continue;
            }
            try {
                agents.delete(s.secretaryAgentId(), s.owner());
                log.info("removed the inbox secretary agent {} of {}", s.secretaryAgentId(), s.owner());
            } catch (AgentNotFoundException e) {
                // already deleted by the user
            }
            sources.save(s.withSecretary(null));
        }
    }
}
