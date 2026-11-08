---
title: Roadmap
description: What SuperAgent doesn't do yet, and what's coming.
---

# Roadmap

SuperAgent iterates fast. This is what isn't built yet, so the rest of the docs only describe what works today.

## Collaboration and continuity

- **Handover when someone leaves** — move a person's agents, their context and their files to a successor, who carries on from there.
- **Company-wide folders** — shared folders beyond each person's own.
- **Projects, further** — spend per project on the analytics page, and your SuperAgent setting up a project as part of a plan.

## Knowledge

- **Company-wide knowledge** — distill what everyone's work produces into knowledge the whole company can use, beyond each agent's own memory.

## Cost

- **A model gateway** — every model call goes through one place in SuperAgent, so keys never reach the agents, usage is metered in one place, and a company's own model gateway can sit behind it.

## Governance and security

- **Enterprise approval workflows** — connect key actions to the company's approval process.
- **Isolation** — run each employee's agents in their own sandbox (microVM, gVisor or Docker), with outbound traffic through a proxy and no long-lived secrets inside.
- **Many machines and many companies** — Workers on several machines with scheduling in the control plane, and one control plane serving many companies. See [Architecture](./architecture).

## Sign-in and organization

- **More ways to sign in** — phone verification codes, SSO, WeChat. Today it's username and password.
- **Org chart sync** — bring teams and reporting lines in from the company's directory.

## Models and connections

- **More CLIs running work** — CodeBuddy, MiniMax Code and Gemini CLI, which can already be installed.
- **More systems** — more company apps under Integrations, in the same way as GitHub, Gitee and Feishu.
